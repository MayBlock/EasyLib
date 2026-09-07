package com.github.mayblock.easylib.redis

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

/**
 * [RedisScope] 的唯一实现。行为契约写在接口上，这里只放实现细节。
 *
 * 每次 [RedisClient.execute] 新建一个实例；它只持有两个引用、不含可变状态，
 * 因此不跨调用共享。
 */
internal class DefaultRedisScope(
    private val redisson: RedissonClient,
    private val metrics: MetricsRecorder,
) : RedisScope, RedissonClient by redisson {

    override suspend fun <T> withLock(
        name: String,
        waitTime: Duration,
        block: suspend RedisScope.() -> T,
    ): T {
        val lock = redisson.getLock(name)
        val locked = lock.tryLockAsync(waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS).await()
        check(locked) { "Redisson lock failed: $name" }
        var primary: Throwable? = null
        try {
            return block()
        } catch (e: Throwable) {
            primary = e
            throw e
        } finally {
            try {
                withContext(NonCancellable) { lock.unlockAsync().await() }
            } catch (unlockFailure: Throwable) {
                // 解锁失败不得顶替 block 抛出的原始异常——否则调用方只看到「锁没放掉」，
                // 查不到真正的故障原因，外层 withRetry 也会按错误的异常类型分类。
                if (primary != null) {
                    primary.addSuppressed(unlockFailure)
                } else {
                    throw unlockFailure
                }
            }
        }
    }

    override suspend fun <T> withRetry(
        times: Int,
        name: String?,
        block: suspend RedisScope.() -> T,
    ): T {
        require(times >= 1) { "times must be at least 1: $times" }
        repeat(times - 1) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                // 必须排在 catch (Exception) 之前：CancellationException 是普通 Exception
                // 子类，顺序反了协程取消就会被当成一次可重试的失败。
                throw e
            } catch (e: Exception) {
                if (name != null) {
                    logger.warn("Retry ${attempt + 1} [$name]", e)
                } else {
                    logger.warn("Retry ${attempt + 1}", e)
                }
            }
        }
        return block()
    }

    override suspend fun <T> withMetrics(
        operation: String,
        block: suspend RedisScope.() -> T,
    ): T = metrics.recordSuspending(operation) { block() }

    private companion object {
        private val logger: Logger = LoggerFactory.getLogger(DefaultRedisScope::class.java)
    }
}
