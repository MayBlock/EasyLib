package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import kotlinx.coroutines.CancellationException
import org.redisson.api.RedissonClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
