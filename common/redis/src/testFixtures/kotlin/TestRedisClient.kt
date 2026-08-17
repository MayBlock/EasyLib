package com.github.mayblock.easylib.redis.testing

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.redis.RedisClient
import org.redisson.api.RBucket
import org.redisson.api.RFuture
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.redisson.api.listener.MessageListener
import org.redisson.client.codec.Codec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** [RedisHooks] 能观测/注入的 Redis 操作。 */
enum class RedisOp {
    /** `RTopic.publishAsync` */
    PUBLISH,

    /** `RTopic.addListenerAsync(Class, MessageListener)` */
    ADD_LISTENER,

    /** `RTopic.removeListenerAsync(Integer...)` */
    REMOVE_LISTENER,

    /** `RBucket.getAsync` */
    BUCKET_GET,
}

/**
 * [TestRedisClient] 的观测与故障注入面。
 *
 * 所有真实 Redis 调用在**发出之前**都会经过这里：钩子抛出的异常就是注入的故障，此时真实调用
 * 不会发生（注册监听器失败 → Redis 上确实没有监听器；注销失败 → 监听器确实残留在 Redis 上），
 * 因此被测代码看到的失败形态与真实网络故障一致，而不是 mock 编造出来的返回值。
 */
class RedisHooks {

    private val hooks = CopyOnWriteArrayList<(name: String, op: RedisOp) -> Unit>()
    private val calls = ConcurrentHashMap<Pair<String, RedisOp>, AtomicInteger>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<MessageListener<*>>>()

    /** 注册一个钩子：对键/频道 [name] 执行任意操作前调用，抛异常即注入故障。 */
    fun before(hook: (name: String, op: RedisOp) -> Unit) {
        hooks += hook
    }

    /** 让 [name] 上的第一次 [op] 失败（抛出 [error]），之后恢复正常。 */
    fun failOnce(name: String, op: RedisOp, error: () -> Throwable = { RuntimeException("redis down") }) {
        val fired = AtomicInteger()
        before { n, o -> if (n == name && o == op && fired.getAndIncrement() == 0) throw error() }
    }

    /** 让 [name] 上的每一次 [op] 都失败。 */
    fun failAlways(name: String, op: RedisOp, error: () -> Throwable = { RuntimeException("redis down") }) {
        before { n, o -> if (n == name && o == op) throw error() }
    }

    /** [name] 上 [op] 被**尝试**的次数（含被注入故障拦下的那些）。 */
    fun calls(name: String, op: RedisOp): Int = calls[name to op]?.get() ?: 0

    /**
     * 曾经通过 `addListenerAsync` 注册到频道 [channel] 的监听器（按注册顺序，含已注销的、
     * 含因注入故障未真正注册成功的）。用于绕过 Redis 直接驱动监听器回调，例如模拟 Redisson
     * 送来畸形消息体。
     */
    @Suppress("UNCHECKED_CAST")
    fun listenersOn(channel: String): List<MessageListener<String>> =
        listeners[channel]?.toList().orEmpty() as List<MessageListener<String>>

    internal fun fire(name: String, op: RedisOp) {
        calls.computeIfAbsent(name to op) { AtomicInteger() }.incrementAndGet()
        hooks.forEach { it(name, op) }
    }

    internal fun recordListener(channel: String, listener: MessageListener<*>) {
        listeners.computeIfAbsent(channel) { CopyOnWriteArrayList() } += listener
    }
}

/**
 * 连到测试 Redis 的 [RedisClient]：底层是真实 Redisson，只在 `getTopic` / `getBucket` 返回的对象上
 * 包一层薄代理，把 [RedisOp] 列出的几个操作接到 [hooks] 上；其余一切原样透传。
 *
 * 由 [RequiresRedis] 注入（可声明 `TestRedisClient` 或 `RedisClient` 参数），或用
 * [RedisTestSupport.newClient] 创建。
 */
class TestRedisClient internal constructor(
    real: RedissonClient,
    override val metrics: MetricsRecorder,
) : RedisClient() {

    val hooks = RedisHooks()

    override val redisson: RedissonClient = InstrumentedRedisson(real, hooks)

    private class InstrumentedRedisson(
        private val real: RedissonClient,
        private val hooks: RedisHooks,
    ) : RedissonClient by real {
        override fun getTopic(name: String): RTopic = InstrumentedTopic(real.getTopic(name), name, hooks)
        override fun getTopic(name: String, codec: Codec): RTopic =
            InstrumentedTopic(real.getTopic(name, codec), name, hooks)

        override fun <V : Any?> getBucket(name: String): RBucket<V> =
            InstrumentedBucket(real.getBucket(name), name, hooks)

        override fun <V : Any?> getBucket(name: String, codec: Codec): RBucket<V> =
            InstrumentedBucket(real.getBucket(name, codec), name, hooks)
    }

    private class InstrumentedTopic(
        private val real: RTopic,
        private val name: String,
        private val hooks: RedisHooks,
    ) : RTopic by real {
        override fun publishAsync(message: Any): RFuture<Long> {
            hooks.fire(name, RedisOp.PUBLISH)
            return real.publishAsync(message)
        }

        override fun <M> addListenerAsync(type: Class<M>, listener: MessageListener<out M>): RFuture<Int> {
            hooks.recordListener(name, listener)
            hooks.fire(name, RedisOp.ADD_LISTENER)
            return real.addListenerAsync(type, listener)
        }

        override fun removeListenerAsync(vararg listenerIds: Int?): RFuture<Void> {
            hooks.fire(name, RedisOp.REMOVE_LISTENER)
            return real.removeListenerAsync(*listenerIds)
        }
    }

    private class InstrumentedBucket<V>(
        private val real: RBucket<V>,
        private val name: String,
        private val hooks: RedisHooks,
    ) : RBucket<V> by real {
        override fun getAsync(): RFuture<V> {
            hooks.fire(name, RedisOp.BUCKET_GET)
            return real.getAsync()
        }
    }
}
