package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.redisson.api.RBucket
import org.redisson.api.RedissonClient
import org.redisson.misc.CompletableFutureWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisClientTest {

    private class TestClient(
        override val redisson: RedissonClient,
        override val metrics: MetricsRecorder = NoOpMetricsRecorder,
    ) : RedisClient()

    private fun redisson(shutdown: Boolean = false, shuttingDown: Boolean = false) =
        mockk<RedissonClient>(relaxed = true).also {
            every { it.isShutdown } returns shutdown
            every { it.isShuttingDown } returns shuttingDown
        }

    @Test
    fun `execute 在 RedisScope 中运行块并透传返回值`() = runTest {
        val client = TestClient(redisson())
        assertEquals("ok", client.execute(Dispatchers.Unconfined) { "ok" })
    }

    @Test
    fun `execute 的块内可直接使用 Redisson 原生 API`() = runTest {
        val bucket = mockk<RBucket<String>>()
        every { bucket.getAsync() } returns CompletableFutureWrapper("value")
        val r = redisson()
        every { r.getBucket<String>("k") } returns bucket

        val client = TestClient(r)
        val result = client.execute(Dispatchers.Unconfined) {
            getBucket<String>("k").getAsync().await()
        }
        assertEquals("value", result)
    }

    @Test
    fun `已关闭的客户端拒绝 execute`() = runTest {
        assertFailsWith<IllegalStateException> {
            TestClient(redisson(shutdown = true)).execute(Dispatchers.Unconfined) { Unit }
        }
        assertFailsWith<IllegalStateException> {
            TestClient(redisson(shuttingDown = true)).execute(Dispatchers.Unconfined) { Unit }
        }
    }

    @Test
    fun `destroy 后 execute 抛 IllegalStateException`() = runTest {
        // isShutdown 由 shutdown() 是否真的被调用过驱动，而不是各自独立 stub——
        // 这样才能验证 destroy() 确实调用了 shutdown()，进而让 execute 里的
        // check(!redisson.isShutdown ...) 被真实触发，而不是靠单独 stub isShutdown 模拟。
        var shutdownCalled = false
        val r = mockk<RedissonClient>(relaxed = true)
        every { r.shutdown() } answers { shutdownCalled = true }
        every { r.isShutdown } answers { shutdownCalled }
        val client = TestClient(r)

        client.destroy()

        assertFailsWith<IllegalStateException> {
            client.execute(Dispatchers.Unconfined) { Unit }
        }
        verify(exactly = 1) { r.shutdown() }
    }

    @Test
    fun `destroy 是幂等的`() = runTest {
        val r = redisson()
        val client = TestClient(r)

        client.destroy()
        client.destroy()
        client.destroy()

        verify(exactly = 1) { r.shutdown() }
    }

    @Test
    fun `isDestroyed 反映 destroy 调用状态`() = runTest {
        val client = TestClient(redisson())
        assertFalse(client.isDestroyed)
        client.destroy()
        assertTrue(client.isDestroyed)
    }
}
