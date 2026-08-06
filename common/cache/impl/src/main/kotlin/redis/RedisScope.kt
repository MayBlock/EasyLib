package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Redis 操作的 DSL 作用域。
 *
 * 通过委托 [RedissonClient]，块内可以直接调用 `getBucket`、`getMap` 等原生 API；
 * [withLock] / [withRetry] / [withMetrics] 的块参数 receiver 同样是 [RedisScope]，
 * 因此可以任意顺序、任意层数嵌套：
 *
 * ```
 * client.execute {
 *     withLock("lock:$key") {
 *         withRetry(3) {
 *             withMetrics("cache.get") {
 *                 getBucket<V>(key).getAsync().await()
 *             }
 *         }
 *     }
 * }
 * ```
 */
internal class RedisScope(
    private val redisson: RedissonClient,
    private val metrics: MetricsRecorder,
) : RedissonClient by redisson {

    /**
     * 在分布式锁 [name] 的保护下执行 [block]，最多等待 [waitTime] 获取锁。
     *
     * 全程使用 Redisson 的异步 API，不阻塞线程——阻塞版在 `Dispatchers.IO` 上抢锁
     * 会白占一个线程最长 [waitTime]。
     *
     * 获取失败抛 [IllegalStateException]（此时不会尝试释放）。释放动作在
     * [NonCancellable] 中执行，避免协程被取消时锁泄漏到看门狗超时为止。
     */
    suspend fun <T> withLock(
        name: String,
        waitTime: Duration = 5.seconds,
        block: suspend RedisScope.() -> T,
    ): T {
        val lock = redisson.getLock(name)
        val locked = lock.tryLockAsync(waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS).await()
        check(locked) { "Redisson lock failed: $name" }
        try {
            return block()
        } finally {
            withContext(NonCancellable) { lock.unlockAsync().await() }
        }
    }

    /**
     * 失败重试。[times] 是**总尝试次数**，不是首次之外的重试次数——`withRetry(3)` 最多执行
     * [block] 三次。末次仍失败则原样抛出该次异常。
     *
     * [CancellationException] 一律直接上抛，不计入重试：协程取消不是可重试的失败。
     */
    suspend fun <T> withRetry(times: Int = 3, block: suspend RedisScope.() -> T): T {
        require(times >= 1) { "times must be at least 1: $times" }
        repeat(times - 1) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Retry ${attempt + 1}", e)
            }
        }
        return block()
    }

    private companion object {
        private val logger: Logger = LoggerFactory.getLogger(RedisScope::class.java)
    }
}
