package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.redisson.misc.CompletableFutureWrapper
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisScopeMetricsTest {

    private val recorded = mutableListOf<String>()

    private val recorder = object : MetricsRecorder {
        override fun <T> record(name: String, block: () -> T): T = block()
        override suspend fun <T> recordSuspending(name: String, block: suspend () -> T): T {
            recorded += name
            return block()
        }
    }

    @Test
    fun `withMetrics 上报操作名并透传返回值`() = runTest {
        val scope = RedisScopeImpl(mockk<RedissonClient>(relaxed = true), recorder)
        val result = scope.withMetrics("cache.get") { "ok" }
        assertEquals("ok", result)
        assertEquals(listOf("cache.get"), recorded)
    }

    @Test
    fun `三层嵌套按顺序生效且返回值一路透传`() = runTest {
        val lock = mockk<RLock>()
        every { lock.tryLockAsync(any<Long>(), any<TimeUnit>()) } returns CompletableFutureWrapper(true)
        every { lock.unlockAsync() } returns CompletableFutureWrapper.completedNull()
        val redisson = mockk<RedissonClient>()
        every { redisson.getLock("L") } returns lock

        var attempts = 0
        val scope = RedisScopeImpl(redisson, recorder)
        val result = scope.withLock("L") {
            withRetry(3) {
                withMetrics("cache.get") {
                    attempts++
                    if (attempts < 2) error("boom")
                    "value"
                }
            }
        }

        assertEquals("value", result)
        assertEquals(2, attempts)
        // withMetrics 在 withRetry 内层，因此每次尝试都上报一次
        assertEquals(listOf("cache.get", "cache.get"), recorded)
    }
}
