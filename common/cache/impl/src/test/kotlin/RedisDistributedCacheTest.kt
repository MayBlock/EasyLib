package com.github.mayblock.easylib.cache.impl

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import com.github.mayblock.easylib.redis.RedisClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.redisson.api.RBucket
import org.redisson.api.RedissonClient
import org.redisson.misc.CompletableFutureWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class RedisDistributedCacheTest {

    private class TestClient(
        override val redisson: RedissonClient,
        override val metrics: MetricsRecorder = NoOpMetricsRecorder,
    ) : RedisClient()

    private val bucket = mockk<RBucket<String>>(relaxed = true)
    private val redisson = mockk<RedissonClient>(relaxed = true).also {
        every { it.isShutdown } returns false
        every { it.isShuttingDown } returns false
    }
    private val client = TestClient(redisson)

    private fun cache(ttl: Duration? = null) =
        RedisDistributedCache<Int, String>(client, namespace = "ns", ttl = ttl)

    @Test
    fun `get 按 namespace 与 keyMapper 拼键并返回值`() = runTest {
        every { redisson.getBucket<String>("ns:7") } returns bucket
        every { bucket.getAsync() } returns CompletableFutureWrapper("value")

        assertEquals("value", cache().get(7))
        verify { redisson.getBucket<String>("ns:7") }
    }

    @Test
    fun `get 未命中返回 null`() = runTest {
        every { redisson.getBucket<String>("ns:7") } returns bucket
        every { bucket.getAsync() } returns CompletableFutureWrapper.completedNull()

        assertNull(cache().get(7))
    }

    @Test
    fun `自定义 keyMapper 参与拼键`() = runTest {
        every { redisson.getBucket<String>("ns:K7") } returns bucket
        every { bucket.getAsync() } returns CompletableFutureWrapper("v")

        val c = RedisDistributedCache<Int, String>(
            client, namespace = "ns", keyMapper = { "K$it" },
        )
        assertEquals("v", c.get(7))
    }

    @Test
    fun `ttl 为 null 时 put 不带过期`() = runTest {
        every { redisson.getBucket<String>("ns:7") } returns bucket
        every { bucket.setAsync("v") } returns CompletableFutureWrapper.completedNull()

        cache().put(7, "v")
        verify(exactly = 1) { bucket.setAsync("v") }
    }

    @Test
    fun `ttl 非 null 时 put 带过期`() = runTest {
        val ttl = 5.minutes
        every { redisson.getBucket<String>("ns:7") } returns bucket
        every { bucket.setAsync("v", ttl.toJavaDuration()) } returns
                CompletableFutureWrapper.completedNull()

        cache(ttl).put(7, "v")
        verify(exactly = 1) { bucket.setAsync("v", ttl.toJavaDuration()) }
    }

    @Test
    fun `remove 透传 deleteAsync 的结果`() = runTest {
        every { redisson.getBucket<String>("ns:7") } returns bucket
        every { bucket.deleteAsync() } returns CompletableFutureWrapper(true)

        assertTrue(cache().remove(7))
    }

    @Test
    fun `get 首次失败后由 withRetry 重试并最终成功`() = runTest {
        every { redisson.getBucket<String>("ns:7") } returns bucket
        every { bucket.getAsync() } throws
                RuntimeException("transient failure") andThen
                CompletableFutureWrapper("value")

        assertEquals("value", cache().get(7))
        verify(exactly = 2) { bucket.getAsync() }
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
    fun `get put remove 各自上报正确的 metric 名称`() = runTest {
        val metrics = RecordingMetricsRecorder()
        val metricClient = TestClient(redisson, metrics)
        val metricCache = RedisDistributedCache<Int, String>(metricClient, namespace = "ns")

        every { redisson.getBucket<String>("ns:7") } returns bucket
        every { bucket.getAsync() } returns CompletableFutureWrapper("v")
        every { bucket.setAsync("v") } returns CompletableFutureWrapper.completedNull()
        every { bucket.deleteAsync() } returns CompletableFutureWrapper(true)

        metricCache.get(7)
        metricCache.put(7, "v")
        metricCache.remove(7)

        assertEquals(listOf("cache.get", "cache.put", "cache.remove"), metrics.recorded)
    }
}