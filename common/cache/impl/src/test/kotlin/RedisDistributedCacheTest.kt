package com.github.mayblock.easylib.cache.impl

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.redis.RedisClient
import com.github.mayblock.easylib.redis.testing.RedisOp
import com.github.mayblock.easylib.redis.testing.RedisTestSupport
import com.github.mayblock.easylib.redis.testing.RequiresRedis
import com.github.mayblock.easylib.redis.testing.TestRedisClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * 跑在真实 Redis 上：键长什么样、有没有 TTL、值能不能读回，都直接问 Redis，
 * 而不是断言「调用了哪个 Redisson 方法」。
 *
 * 用 [runBlocking] 而非 `runTest`：TTL 过期依赖真实时钟。
 */
@RequiresRedis
class RedisDistributedCacheTest {

    private fun cache(client: RedisClient, ttl: Duration? = null) =
        RedisDistributedCache<Int, String>(client, namespace = "ns", ttl = ttl)

    /** 直接从 Redis 读某个原始键的值。 */
    private suspend fun RedisClient.rawGet(key: String): String? = execute { getBucket<String>(key).getAsync().await() }

    /** 直接从 Redis 读某个原始键剩余 TTL（毫秒）；-1 表示无过期，-2 表示键不存在。 */
    private suspend fun RedisClient.rawTtl(key: String): Long = execute { getBucket<String>(key).remainTimeToLiveAsync().await() }

    @Test
    fun `put 按 namespace 拼键写入，get 读回同一个值`(client: TestRedisClient) = runBlocking {
        cache(client).put(7, "value")

        assertEquals("value", client.rawGet("ns:7"))
        assertEquals("value", cache(client).get(7))
    }

    @Test
    fun `get 未命中返回 null`(client: TestRedisClient) = runBlocking {
        assertNull(cache(client).get(404))
    }

    @Test
    fun `自定义 keyMapper 参与拼键`(client: TestRedisClient) = runBlocking {
        val c = RedisDistributedCache<Int, String>(client, namespace = "ns", keyMapper = { "K$it" })

        c.put(7, "v")

        assertEquals("v", client.rawGet("ns:K7"))
        assertEquals("v", c.get(7))
    }

    @Test
    fun `不同 namespace 互不可见`(client: TestRedisClient) = runBlocking {
        val a = RedisDistributedCache<Int, String>(client, namespace = "a")
        val b = RedisDistributedCache<Int, String>(client, namespace = "b")

        a.put(1, "from-a")

        assertNull(b.get(1))
    }

    @Test
    fun `复合值经 Redisson 默认编解码往返`(client: TestRedisClient) = runBlocking {
        val c = RedisDistributedCache<String, Map<String, List<Int>>>(client, namespace = "ns")
        val value = mapOf("a" to listOf(1, 2, 3), "b" to emptyList())

        c.put("k", value)

        assertEquals(value, c.get("k"))
    }

    @Test
    fun `ttl 为 null 时 put 不带过期`(client: TestRedisClient) = runBlocking {
        cache(client).put(7, "v")

        assertEquals(-1L, client.rawTtl("ns:7"))
    }

    @Test
    fun `ttl 非 null 时 put 带过期`(client: TestRedisClient) = runBlocking {
        cache(client, ttl = 5.minutes).put(7, "v")

        val ttl = client.rawTtl("ns:7")
        assertTrue(ttl in 1..5.minutes.inWholeMilliseconds, "unexpected TTL: $ttl")
    }

    @Test
    fun `带 TTL 的值到期后由 Redis 过期`(client: TestRedisClient) = runBlocking {
        val c = cache(client, ttl = 300.milliseconds)
        c.put(1, "short-lived")
        assertEquals("short-lived", c.get(1))

        delay(700)

        assertNull(c.get(1))
    }

    @Test
    fun `remove 删除已有键返回 true，再删返回 false`(client: TestRedisClient) = runBlocking {
        val c = cache(client)
        c.put(2, "two")

        assertTrue(c.remove(2))
        assertNull(client.rawGet("ns:2"))
        assertFalse(c.remove(2))
    }

    @Test
    fun `get 首次失败后由 withRetry 重试并最终成功`(client: TestRedisClient) = runBlocking {
        cache(client).put(7, "value")
        client.hooks.failOnce("ns:7", RedisOp.BUCKET_GET) { RuntimeException("transient failure") }

        assertEquals("value", cache(client).get(7))
        assertEquals(2, client.hooks.calls("ns:7", RedisOp.BUCKET_GET))
    }

    private class RecordingMetricsRecorder : MetricsRecorder {
        val recorded = mutableListOf<String>()

        override fun <T> record(name: String, block: () -> T): T = block()

        override suspend fun <T> recordSuspending(name: String, block: suspend () -> T): T {
            recorded += name
            return block()
        }
    }

    @Test
    fun `get put remove 各自上报正确的 metric 名称`() = runBlocking {
        val metrics = RecordingMetricsRecorder()
        val client = RedisTestSupport.newClient(metrics)
        try {
            val c = cache(client)

            c.get(7)
            c.put(7, "v")
            c.remove(7)

            assertEquals(listOf("cache.get", "cache.put", "cache.remove"), metrics.recorded)
        } finally {
            client.destroy()
        }
    }
}
