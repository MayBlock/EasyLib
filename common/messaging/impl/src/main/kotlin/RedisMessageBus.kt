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
import kotlin.reflect.KClass

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

    /** 频道 -> Redisson listener id。 */
    private val listeners = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val joined = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mutex = Mutex()

    @Volatile
    private var destroyed = false

    override val isDestroyed: Boolean
        get() = destroyed

    override val groups: Set<String>
        get() = joined.toSet()

    override suspend fun publish(target: Target, message: Any) {
        val json = codec.encode(instanceId, message)
        val channel = ChannelNames.of(namespace, target)
        client.execute {
            withRetry(name = "messaging.publish") {
                withMetrics("messaging.publish") {
                    getTopic(channel, StringCodec.INSTANCE).publishAsync(json).await()
                }
            }
        }
    }

    override fun <M : Any> subscribe(type: KClass<M>, includeSelf: Boolean): Flow<Envelope<M>> {
        // 立即解析，让缺注解／线上名冲突在 subscribe 处就抛出，而不是等到 collect 才炸。
        val wireName = codec.wireNameOf(type)
        return inbound
            .filter { it.type == wireName }
            .filter { includeSelf || it.sender != instanceId }
            // 解码失败返回 null 即丢弃。M : Any 保证合法 payload 不可能是 null，
            // 所以 null 只可能来自失败，两种情况不会混淆。
            .mapNotNull { codec.toEnvelope(it, type) }
    }

    override suspend fun joinGroup(name: String) {
        mutex.withLock {
            if (!joined.add(name)) return
            addListener(ChannelNames.of(namespace, Target.Group(name)))
        }
    }

    override suspend fun leaveGroup(name: String) {
        mutex.withLock {
            if (!joined.remove(name)) return
            removeListener(ChannelNames.of(namespace, Target.Group(name)))
        }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        // 注销监听器是 I/O，而 Destroyable.destroy() 不可挂起，也就进不了 client.execute。
        // 走 topicForShutdown 拿同步的 RTopic.removeListener(Integer...)，
        // 仅在关停路径上执行这一次。
        val snapshot = synchronized(listeners) { listeners.toMap().also { listeners.clear() } }
        snapshot.forEach { (channel, id) ->
            runCatching { client.topicForShutdown(channel, StringCodec.INSTANCE).removeListener(id) }
                .onFailure { logger.warn("Failed to remove listener on {}", channel, it) }
        }
    }

    /** 调用方必须持有 [mutex]。 */
    private suspend fun addListener(channel: String) {
        val listener = MessageListener<String> { _, body ->
            // 这里跑在 Redisson 的 Netty 事件循环线程上：不阻塞、不挂起、不让异常逃逸。
            // 异常逃逸会刷屏，严重时打掉监听器。
            try {
                codec.decodeEnvelope(body)?.let(inbound::tryEmit)
            } catch (e: Throwable) {
                logger.warn("Listener on {} failed to handle a message", channel, e)
            }
        }
        val id = client.execute {
            getTopic(channel, StringCodec.INSTANCE)
                .addListenerAsync(String::class.java, listener)
                .await()
        }
        listeners[channel] = id
    }

    /** 调用方必须持有 [mutex]。 */
    private suspend fun removeListener(channel: String) {
        val id = synchronized(listeners) { listeners.remove(channel) } ?: return
        client.execute {
            getTopic(channel, StringCodec.INSTANCE).removeListenerAsync(id).await()
        }
    }

    companion object {
        private const val INBOUND_BUFFER = 256
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
            bus.mutex.withLock {
                bus.addListener(ChannelNames.of(namespace, Target.All))
                bus.addListener(ChannelNames.of(namespace, Target.Instance(instanceId)))
            }
            initialGroups.forEach { bus.joinGroup(it) }
            return bus
        }
    }
}
