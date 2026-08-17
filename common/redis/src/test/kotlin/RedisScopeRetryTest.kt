package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.redis.testing.RequiresRedis
import com.github.mayblock.easylib.redis.testing.TestRedisClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** withRetry 是纯控制流；这里只借真实客户端拿到一个 [RedisScope]。 */
@RequiresRedis
class RedisScopeRetryTest {

    @Test
    fun `第 N 次成功时恰好调用 N 次`(client: TestRedisClient) = runBlocking {
        var calls = 0
        val result = client.execute {
            withRetry(3) {
                calls++
                if (calls < 3) error("boom")
                "ok"
            }
        }
        assertEquals(3, calls)
        assertEquals("ok", result)
    }

    @Test
    fun `首次成功时只调用一次`(client: TestRedisClient) = runBlocking {
        var calls = 0
        client.execute { withRetry(3) { calls++ } }
        assertEquals(1, calls)
    }

    @Test
    fun `全部失败时抛出末次异常且调用次数等于 times`(client: TestRedisClient) = runBlocking {
        var calls = 0
        val e = assertFailsWith<IllegalStateException> {
            client.execute {
                withRetry(3) {
                    calls++
                    error("boom $calls")
                }
            }
        }
        assertEquals(3, calls)
        assertEquals("boom 3", e.message)
    }

    @Test
    fun `times 小于 1 抛 IllegalArgumentException 且不执行块`(client: TestRedisClient) = runBlocking {
        var calls = 0
        assertFailsWith<IllegalArgumentException> { client.execute { withRetry(0) { calls++ } } }
        assertFailsWith<IllegalArgumentException> { client.execute { withRetry(-5) { calls++ } } }
        assertEquals(0, calls)
    }

    @Test
    fun `CancellationException 立即上抛且不消耗重试次数`(client: TestRedisClient) = runBlocking {
        var calls = 0
        assertFailsWith<CancellationException> {
            client.execute {
                withRetry(3) {
                    calls++
                    throw CancellationException("cancelled")
                }
            }
        }
        assertEquals(1, calls)
    }
}
