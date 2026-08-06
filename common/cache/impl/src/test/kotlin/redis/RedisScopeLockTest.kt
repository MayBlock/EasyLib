package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.redisson.misc.CompletableFutureWrapper
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedisScopeLockTest {

    private val lock = mockk<RLock>()
    private val redisson = mockk<RedissonClient>()

    private fun scope(acquired: Boolean): RedisScope {
        every { lock.tryLockAsync(any<Long>(), any<TimeUnit>()) } returns
            CompletableFutureWrapper(acquired)
        every { lock.unlockAsync() } returns CompletableFutureWrapper.completedNull()
        every { redisson.getLock("L") } returns lock
        return RedisScope(redisson, NoOpMetricsRecorder)
    }

    @Test
    fun `拿到锁时执行块并释放锁`() = runTest {
        val result = scope(acquired = true).withLock("L") { "ok" }
        assertEquals("ok", result)
        verify(exactly = 1) { lock.unlockAsync() }
    }

    @Test
    fun `块抛异常时仍然释放锁`() = runTest {
        assertFailsWith<IllegalStateException> {
            scope(acquired = true).withLock("L") { error("boom") }
        }
        verify(exactly = 1) { lock.unlockAsync() }
    }

    @Test
    fun `抢锁失败时抛 IllegalStateException 且不释放锁`() = runTest {
        assertFailsWith<IllegalStateException> {
            scope(acquired = false).withLock("L") { "unreachable" }
        }
        verify(exactly = 0) { lock.unlockAsync() }
    }
}
