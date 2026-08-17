package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.messaging.api.Envelope
import com.github.mayblock.easylib.messaging.api.MessageBus
import com.github.mayblock.easylib.messaging.api.Target
import com.github.mayblock.easylib.redis.RedisClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.redisson.api.listener.MessageListener
import org.redisson.client.codec.StringCodec
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于 Redis Pub/Sub 的 [MessageBus]。
 *
 * 所有 Redis 频道的入站消息汇聚到一个 [MutableSharedFlow]，订阅方从中按类型过滤。这样
 * [joinGroup] 只需往这个 flow 多接一个源，已经在 collect 的订阅方自动开始收到新群组的消息，
 * 不需要重建任何东西；若改成每个订阅方各自注册 Redis 监听器，动态群组就要回头重配每一个
 * 活着的 Flow，且同一频道 N 个订阅方意味着 N 次信封解析。
 *
 * 用 [create] 构造——注册 Redis 监听器是 I/O，构造必须可挂起。
 */
class RedisMessageBus private constructor(
    private val client: RedisClient,
    override val instanceId: String,
    private val namespace: String,
) : MessageBus {

    private val codec = MessageCodec()

    /**
     * `internal` (rather than `private`) solely so same-module tests can collect it to verify
     * the Netty-thread listener callback actually delivers/discards messages correctly. Not
     * part of any public contract.
     */
    internal val inbound = MutableSharedFlow<WireEnvelope>(
        replay = 0,
        extraBufferCapacity = INBOUND_BUFFER,
        // 绝不能因为某个慢订阅方而阻塞 Redisson 的 Netty 线程。缓冲满时丢最老的，
        // 与本总线「允许丢失」的整体语义一致。
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 频道 -> Redisson listener id。
     *
     * 这是订阅状态的**唯一**事实来源：[groups] 从这里的键反推（[ChannelNames.groupOf]），
     * 不单独维护群组集合。两份状态靠手工同步的年代，destroy 与 joinGroup 的竞态会让
     * 群组集合残留已收回监听器的名字——单一来源让这类失同步在结构上不可能发生。
     */
    private val listeners = ConcurrentHashMap<String, Int>()
    private val mutex = Mutex()

    @Volatile
    private var destroyed = false

    override val isDestroyed: Boolean
        get() = destroyed

    override val groups: Set<String>
        get() = listeners.keys.mapNotNullTo(mutableSetOf()) { ChannelNames.groupOf(namespace, it) }

    /**
     * 至多一次投递，**不重试**：PUBLISH 可能已经送达、只是响应丢了，这时重试会把同一条
     * 消息（同一 envelope id）重复扇出给所有订阅方。本总线的契约是「允许丢失」而非
     * 「允许重复」——瞬时故障下丢一条在契约之内，重复投递则会让非幂等的处理器出错。
     */
    override suspend fun publish(target: Target, message: Any) {
        check(!destroyed) { "MessageBus has been destroyed" }
        val json = codec.encode(instanceId, message)
        val channel = ChannelNames.of(namespace, target)
        client.execute {
            withMetrics("messaging.publish") {
                getTopic(channel, StringCodec.INSTANCE).publishAsync(json).await()
            }
        }
    }

    override fun <M : Any> subscribe(type: Class<M>, includeSelf: Boolean): Flow<Envelope<M>> {
        // 关停后拒绝新订阅：既与 publish/joinGroup/leaveGroup 一致（返回一个永不产出的 Flow
        // 会被误读成「没有消息」而不是「总线已关停」），也避免 wireNameOf 把消息类重新登记进
        // codec 的注册表、再次钉住已经该被回收的 classloader。
        check(!destroyed) { "MessageBus has been destroyed" }
        // 立即解析，让缺注解／线上名冲突在 subscribe 处就抛出，而不是等到 collect 才炸
        val wireName = codec.wireNameOf(type)
        return inbound
            .filter { it.type == wireName }
            .filter { includeSelf || it.sender != instanceId }
            // 解码失败返回 null 即丢弃。M : Any 保证合法 payload 不可能是 null，
            // 所以 null 只可能来自失败，两种情况不会混淆。
            .mapNotNull { codec.toEnvelope(it, type) }
    }

    override suspend fun joinGroup(name: String) {
        check(!destroyed) { "MessageBus has been destroyed" }
        mutex.withLock {
            val channel = ChannelNames.of(namespace, Target.Group(name))
            // 幂等检查直接看监听器表：注册失败时 addListener 不会留下任何记录，
            // 重试自然畅通，无需回滚逻辑。
            if (listeners.containsKey(channel)) return
            addListener(channel)
        }
    }

    override suspend fun leaveGroup(name: String) {
        check(!destroyed) { "MessageBus has been destroyed" }
        mutex.withLock {
            removeListener(ChannelNames.of(namespace, Target.Group(name)))
        }
    }

    /**
     * 关停：注销全部 Redis 监听器并清空群组。
     *
     * 幂等——重复调用是空操作。
     *
     * 关停后的契约：[isDestroyed] 为 true，[groups] 为空，入站消息不再到达任何订阅方
     * （监听器回调里有 destroyed 闸门，即使某个监听器注销失败残留在 Redis 上也不例外）；
     * [publish] / [joinGroup] / [leaveGroup] 会因本类自己的 `check(!destroyed)` 抛
     * [IllegalStateException]——这个总线共享底层 [RedisClient]（与 cache 模块共用），
     * 因此不会去关停 client 本身，[RedisClient.execute] 的关停校验对它不生效。
     * 已存在的订阅 Flow 不会主动结束，只是不再有新消息——它们由各自的 collect 作用域取消。
     *
     * 单个频道注销失败只记 warn 并继续处理其余频道，不让一个坏频道卡住整个关停。
     * 注销请求对全部频道**并行**发出，然后统一等待至多 [DESTROY_TIMEOUT_SECONDS] 秒——
     * 常见调用点是 Bukkit 主线程上的 `onDisable()`，逐个频道同步等待会在 Redis 不可达时
     * 让关服停顿「频道数 × 命令超时」之久；超时未完成的注销同样只记 warn，正确性由
     * 监听器回调的 destroyed 闸门与 [MessageCodec.close] 兜底。
     */
    override fun destroy() {
        if (destroyed) return
        destroyed = true
        // 注销监听器是 I/O，而 Destroyable.destroy() 不可挂起，也就进不了 client.execute。
        // 走 topicForShutdown 拿 RTopic 的异步注销，仅在关停路径上执行。
        // listeners 是 ConcurrentHashMap，取键快照后逐个 remove 即可，无需外部加锁。
        val pending = listeners.keys.toList().mapNotNull { channel ->
            val id = listeners.remove(channel) ?: return@mapNotNull null
            removeListenerBestEffort(channel, id)
        }
        awaitBestEffort(pending)
        // 断开对消息类的强引用。监听器 lambda 捕获了 codec，而 Redisson 可能因为上面某次
        // 注销失败而仍然攥着它——close 同时切断注册表与 Jackson 缓存两条强引用链，
        // 让插件的 classloader 无论如何都能被回收（见 MessageCodec.close 的 KDoc）。
        codec.close()
    }

    /** 调用方必须持有 [mutex]。 */
    private suspend fun addListener(channel: String) {
        val listener = MessageListener<String> { _, body ->
            // 这里跑在 Redisson 的 Netty 事件循环线程上：不阻塞、不挂起、不让异常逃逸。
            // 异常逃逸会刷屏，严重时打掉监听器。
            //
            // destroyed 闸门：「销毁后入站消息不再到达订阅方」不能依赖注销成功——注销
            // 失败只记 warn，残存的监听器重连后仍会收到消息；也不能等注销 I/O 做完。
            // destroyed 一置位这里就静默丢弃，契约与时序、网络都无关。
            if (destroyed) return@MessageListener
            try {
                codec.decodeEnvelope(body)?.let(inbound::tryEmit)
            } catch (e: Throwable) {
                logger.warn("Listener on {} failed to handle a message", channel, e)
            }
        }
        // 只计量、不重试：注册的响应若丢失，重试会在同一频道注册出第二个监听器、
        // 却只记得住一个 id——留下一个收不回的重复投递源。
        val id = client.execute {
            withMetrics("messaging.listener.add") {
                getTopic(channel, StringCodec.INSTANCE)
                    .addListenerAsync(String::class.java, listener)
                    .await()
            }
        }
        listeners[channel] = id

        // destroy() 拿不到 mutex（它不可挂起），所以它可能整个跑完在上面这次 await 之后、
        // 这行记录之前——那样这个监听器就会漏在 Redis 上，而 bus 已经声称自己关停了。
        // 这里补一次检查把它收回来（不等待完成：失败同样只记 warn）。
        if (destroyed && listeners.remove(channel) != null) {
            removeListenerBestEffort(channel, id)
        }
    }

    /**
     * 尽力而为的异步注销，供 [destroy] 与 [addListener] 的关停竞态收尾使用；失败只记 warn。
     *
     * 返回注销的 future 供 [destroy] 统一限时等待；发起动作本身就失败时返回 null。
     */
    private fun removeListenerBestEffort(channel: String, id: Int): java.util.concurrent.CompletableFuture<Void>? =
        try {
            client.topicForShutdown(channel, StringCodec.INSTANCE)
                .removeListenerAsync(id)
                .toCompletableFuture()
                .whenComplete { _, e ->
                    if (e != null) logger.warn("Failed to remove listener on {}", channel, e)
                }
        } catch (e: Throwable) {
            logger.warn("Failed to remove listener on {}", channel, e)
            null
        }

    /** 统一等待注销完成，至多 [DESTROY_TIMEOUT_SECONDS] 秒；超时/失败只记 warn，不上抛。 */
    private fun awaitBestEffort(pending: List<java.util.concurrent.CompletableFuture<Void>>) {
        if (pending.isEmpty()) return
        try {
            java.util.concurrent.CompletableFuture.allOf(*pending.toTypedArray())
                .get(DESTROY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while removing listeners during destroy")
        } catch (e: Exception) {
            // 个别失败已在 whenComplete 里逐条记过日志；这里只汇总一次。
            logger.warn("Some listeners were not removed within {}s during destroy", DESTROY_TIMEOUT_SECONDS)
        }
    }

    /**
     * 调用方必须持有 [mutex]。
     *
     * 先做 I/O 再从 [listeners] 摘除：若 I/O 失败，监听器仍然活在 Redis 上，[listeners] 就必须
     * 如实保留这条记录，否则会既漏发一次真正的注销、又让后续 [joinGroup] 在同一频道上注册出
     * 第二个监听器，导致消息重复投递。
     */
    private suspend fun removeListener(channel: String) {
        val id = listeners[channel] ?: return
        client.execute {
            withMetrics("messaging.listener.remove") {
                getTopic(channel, StringCodec.INSTANCE).removeListenerAsync(id).await()
            }
        }
        listeners.remove(channel)
    }

    companion object {
        private const val INBOUND_BUFFER = 256
        private const val DESTROY_TIMEOUT_SECONDS = 5L
        private val logger: Logger = LoggerFactory.getLogger(RedisMessageBus::class.java)

        /**
         * 建立总线并订阅 `all`、本实例、以及 [initialGroups] 各频道。
         *
         * [instanceId] 必须跨重启稳定——[Target.Instance] 寻址依赖它，所以不能由库随机生成。
         */
        suspend fun create(
            client: RedisClient,
            instanceId: String,
            namespace: String,
            initialGroups: Set<String> = emptySet(),
        ): RedisMessageBus {
            val bus = RedisMessageBus(client, instanceId, namespace)
            try {
                bus.mutex.withLock {
                    bus.addListener(ChannelNames.of(namespace, Target.All))
                    bus.addListener(ChannelNames.of(namespace, Target.Instance(instanceId)))
                }
                initialGroups.forEach { bus.joinGroup(it) }
            } catch (e: Throwable) {
                // 部分订阅失败：已经注册成功的监听器不能留在 Redis 上没人认领——bus 本身
                // 因为这次 create 失败而永远拿不到，调用方一般会在启动循环里重试 create。
                bus.destroy()
                throw e
            }
            return bus
        }
    }
}
