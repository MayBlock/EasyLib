package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.cache.impl.redis.RedisClient.Companion.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

abstract class RedisClient {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(RedisClient::class.java)
    }

    abstract val metrics: MetricsRecorder
    protected abstract val redisson: RedissonClient

    internal fun <T> execute(block: RedissonClient.() -> T): T {
        check(redisson.isShutdown.not() && redisson.isShuttingDown.not()) { "Redisson client is shutdown" }
        return redisson.block()
    }

    internal suspend fun <T> suspendExecute(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend RedissonClient.() -> T
    ): T {
        check(redisson.isShutdown.not() && redisson.isShuttingDown.not()) { "Redisson client is shutdown" }
        return withContext(dispatcher) {
            redisson.block()
        }
    }

    internal inline fun <T> withLock(
        lockName: String,
        block: RedissonClient.() -> T
    ): T {
        val lock = redisson.getLock(lockName)
        var locked = false
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS)
            if (!locked) {
                throw IllegalStateException("Redisson lock failed: $lockName")
            }
            return redisson.block()
        } finally {
            if (locked) {
                lock.unlock()
            }
        }
    }

    internal inline fun <T> withRetry(
        retry: Int = 3,
        block: RedissonClient.() -> T
    ): T {
        repeat(retry - 1) {
            try {
                return redisson.block()
            } catch (e: Exception) {
                logger.warn("Retry ${it + 1}", e)
            }
        }
        return redisson.block()
    }

    internal fun <T> withMetrics(
        operation: String,
        block: RedissonClient.() -> T
    ): T = metrics.record(operation) {
        redisson.block()
    }
}