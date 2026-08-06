package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.redisson.api.RedissonClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedisScopeRetryTest {

    private fun scope() = RedisScope(mockk<RedissonClient>(relaxed = true), NoOpMetricsRecorder)

    @Test
    fun `第 N 次成功时恰好调用 N 次`() = runTest {
        var calls = 0
        val result = scope().withRetry(3) {
            calls++
            if (calls < 3) error("boom")
            "ok"
        }
        assertEquals(3, calls)
        assertEquals("ok", result)
    }

    @Test
    fun `首次成功时只调用一次`() = runTest {
        var calls = 0
        scope().withRetry(3) { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `全部失败时抛出末次异常且调用次数等于 times`() = runTest {
        var calls = 0
        val e = assertFailsWith<IllegalStateException> {
            scope().withRetry(3) {
                calls++
                error("boom $calls")
            }
        }
        assertEquals(3, calls)
        assertEquals("boom 3", e.message)
    }

    @Test
    fun `times 小于 1 抛 IllegalArgumentException 且不执行块`() = runTest {
        var calls = 0
        assertFailsWith<IllegalArgumentException> { scope().withRetry(0) { calls++ } }
        assertFailsWith<IllegalArgumentException> { scope().withRetry(-5) { calls++ } }
        assertEquals(0, calls)
    }

    @Test
    fun `CancellationException 立即上抛且不消耗重试次数`() = runTest {
        var calls = 0
        assertFailsWith<CancellationException> {
            scope().withRetry(3) {
                calls++
                throw CancellationException("cancelled")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `带 name 参数时行为与不带 name 时一致`() = runTest {
        var calls = 0
        val result = scope().withRetry(times = 3, name = "cache.get") {
            calls++
            if (calls < 2) error("boom")
            "ok"
        }
        assertEquals(2, calls)
        assertEquals("ok", result)
    }

    @Test
    fun `name 为 null 时仍可正常重试`() = runTest {
        var calls = 0
        val result = scope().withRetry(times = 2, name = null) {
            calls++
            if (calls < 2) error("boom")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }
}
