package com.github.mayblock.easylib.messaging.impl

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.mayblock.easylib.messaging.api.Envelope
import com.github.mayblock.easylib.messaging.api.MessageType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 信封的编解码，以及消息类到线上名的解析。
 *
 * [idGenerator] 与 [clock] 是为了让编码结果可测——生产使用默认值即可。
 */
internal class MessageCodec(
    private val idGenerator: () -> String = {
        @OptIn(ExperimentalUuidApi::class)
        Uuid.generateV7().toHexString()
    },
    private val clock: () -> Instant = { Clock.System.now() },
) {

    /** 见 [close]。 */
    @Volatile
    private var closed = false

    /**
     * 可空 + `@Volatile`：[close] 把它置 null 以断开序列化器缓存对消息类的强引用，
     * 见 [close] 的说明。除 [close] 外没有任何写入方。
     */
    @Volatile
    private var mapper: ObjectMapper? = ObjectMapper()
        .registerKotlinModule()
        // 支持 payload 里的 java.time 类型（Instant、LocalDateTime 等）——没有这个模块，
        // valueToTree 遇到 java.time 值会抛 IllegalArgumentException，且异常类型与
        // 「缺 @MessageType 注解」那个 IllegalArgumentException 撞了，容易误诊。
        // kotlin.time.Instant / kotlin.time.Duration 这个模块不认，仍不支持——见
        // MessageType 的 KDoc。
        .registerModule(JavaTimeModule())
        // 让 java.time 值以可读的 ISO-8601 上线，而不是纪元数组，与信封自身的 time 字段一致。
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // 新版本新增的字段不能让老实例解码失败——这是「加带默认值的字段属于兼容改动」
        // 这条演进契约成立的前提。
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * 线上名 -> 声明它的类。用于检测两个类抢同一个名字。
     *
     * 注意这里持有的是**强引用的 `Class` 对象**，而 Bukkit 下每个插件有独立 classloader，
     * 一个 `Class` 就钉住整个 classloader。见 [clearRegistry]。
     */
    private val claimedNames = ConcurrentHashMap<String, Class<*>>()

    /**
     * 关闭编解码器：断开本类持有的、指向消息类（进而指向其 classloader）的**全部**强引用。
     *
     * 由 `RedisMessageBus.destroy()` 调用。泄漏链是：Redisson 持有监听器 → 监听器 lambda
     * 捕获本 codec → codec 经**两条**路径强引用插件的 `Class` 对象——[claimedNames]
     * 注册表，以及 [mapper] 内部的序列化器/TypeFactory 缓存（凡 encode/decode 过的类型
     * 都会被缓存）。只清注册表不够：即使某个监听器因故没能从 Redis 摘干净，两条路径
     * 都断开后，插件的 classloader 仍能被回收。
     *
     * 关闭后的行为：[encode] 抛 [IllegalStateException]（与总线 destroy 后 publish 的契约
     * 一致）；[decodeEnvelope] / [toEnvelope] 返回 null，按"坏消息"路径静默丢弃；
     * [wireNameOf] 照常校验注解但**不再写入注册表**——这样即使调用方在 destroy 竞态中
     * 穿过了总线的 destroyed 检查，也没有任何路径能把 `Class` 重新钉回来。
     */
    fun close() {
        closed = true
        claimedNames.clear()
        mapper = null
    }

    /**
     * 取 [type] 的线上名。
     *
     * 全程不做按名查类：注解从调用方给的 Class 对象上读取。Bukkit 下每个插件有独立
     * classloader，`Class.forName` 会用本库的 loader 而不是消息类所属插件的，必然出错。
     */
    fun wireNameOf(type: Class<*>): String {
        val annotation = type.getAnnotation(MessageType::class.java)
        requireNotNull(annotation) {
            "${type.name} is not annotated with @MessageType; " +
                "a message class must declare its wire identity explicitly"
        }
        val name = annotation.value
        // 关闭后只读注解、不登记：登记会把 Class 重新钉进强引用（见 close 的 KDoc）。
        if (closed) return name
        val previous = claimedNames.putIfAbsent(name, type)
        require(previous == null || previous == type) {
            "wire name '$name' is claimed by both ${previous!!.name} and ${type.name}"
        }
        // close() 与本方法之间没有锁：上面的 putIfAbsent 可能恰好落在 close() 清空注册表
        // 之后。这里复查一次，把竞态中挤进来的登记撤掉——与 RedisMessageBus.addListener
        // 里 destroy 竞态收尾是同一个模式。
        if (closed && previous == null) {
            claimedNames.remove(name, type)
        }
        return name
    }

    /**
     * 把 [message] 连同元数据编码成线上 JSON。
     *
     * [close] 之后抛 [IllegalStateException]——发送方必须得到明确失败，而不是静默不发。
     */
    fun encode(senderId: String, message: Any): String {
        val m = checkNotNull(mapper) { "MessageCodec has been closed" }
        val wire = m.createObjectNode().apply {
            put("id", idGenerator())
            put("sender", senderId)
            put("type", wireNameOf(message::class.java))
            put("time", clock().toString())
            set<JsonNode>("payload", m.valueToTree(message))
        }
        return m.writeValueAsString(wire)
    }

    /**
     * 解析线上 JSON 的信封部分，payload 保持未解码。
     *
     * 畸形输入返回 null 并记 warn——一条坏消息不得打断整条订阅。[close] 之后一律返回 null。
     */
    fun decodeEnvelope(json: String): WireEnvelope? {
        val m = mapper ?: return null
        return try {
            m.readValue(json, WireEnvelope::class.java)
        } catch (e: Exception) {
            logger.warn("Discarding malformed message envelope", e)
            null
        }
    }

    /**
     * 把 [wire] 的 payload 解码成 [type]，连同元数据组装成 [Envelope]。
     *
     * 解码失败（版本偏移、字段类型改了、时间戳畸形）返回 null 并记 warn。同样是「一条坏消息
     * 不得打断订阅」——把异常抛进 Flow 会直接取消订阅方的 collect。[close] 之后一律返回 null。
     */
    fun <M : Any> toEnvelope(wire: WireEnvelope, type: Class<M>): Envelope<M>? {
        val m = mapper ?: return null
        val time = try {
            Instant.parse(wire.time)
        } catch (e: Exception) {
            logger.warn("Discarding message {}: malformed time '{}'", wire.id, wire.time, e)
            return null
        }
        return try {
            Envelope(
                payload = m.treeToValue(wire.payload, type),
                id = wire.id,
                senderId = wire.sender,
                time = time,
            )
        } catch (e: Exception) {
            logger.warn(
                "Discarding message {} of type {}: payload does not fit {}",
                wire.id,
                wire.type,
                type.name,
                e,
            )
            null
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(MessageCodec::class.java)
    }
}
