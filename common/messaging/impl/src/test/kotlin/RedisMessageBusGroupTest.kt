package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import com.github.mayblock.easylib.redis.RedisClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.redisson.client.codec.Codec
import org.redisson.misc.CompletableFutureWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisMessageBusGroupTest {

    private class TestClient(
        override val redisson: RedissonClient,
        override val metrics: MetricsRecorder = NoOpMetricsRecorder,
    ) : RedisClient()

    private val redisson = mockk<RedissonClient>(relaxed = true).also {
        every { it.isShutdown } returns false
        every { it.isShuttingDown } returns false
    }

    private val lobbyTopic = mockk<RTopic>(relaxed = true)

    private fun wireUp() {
        every { lobbyTopic.addListenerAsync(String::class.java, any()) } returns CompletableFutureWrapper(7)
        every { lobbyTopic.removeListenerAsync(7) } returns CompletableFutureWrapper.completedNull()
        every { redisson.getTopic("easylib:msg:group:lobby", any<Codec>()) } returns lobbyTopic

        val other = mockk<RTopic>(relaxed = true)
        every { other.addListenerAsync(String::class.java, any()) } returns CompletableFutureWrapper(1)
        every { redisson.getTopic(match { it != "easylib:msg:group:lobby" }, any<Codec>()) } returns other
    }

    private suspend fun bus() = RedisMessageBus.create(
        client = TestClient(redisson),
        instanceId = "me",
        namespace = "easylib",
    )

    @Test
    fun `leaveGroup 注销监听器并更新 groups`() = runTest {
        wireUp()
        val b = bus()
        b.joinGroup("lobby")

        b.leaveGroup("lobby")

        verify(exactly = 1) { lobbyTopic.removeListenerAsync(7) }
        assertTrue(b.groups.isEmpty())
    }

    @Test
    fun `leaveGroup 对未加入的群组是空操作`() = runTest {
        wireUp()
        val b = bus()

        b.leaveGroup("never-joined")

        verify(exactly = 0) { lobbyTopic.removeListenerAsync(7) }
    }

    @Test
    fun `groups 返回快照——之后的 join 不影响已取出的集合`() = runTest {
        wireUp()
        val b = bus()
        b.joinGroup("lobby")
        val snapshot = b.groups

        b.leaveGroup("lobby")

        assertEquals(setOf("lobby"), snapshot)
        assertTrue(b.groups.isEmpty())
    }

    @Test
    fun `destroy 注销全部监听器且幂等`() = runTest {
        wireUp()
        val b = bus()
        b.joinGroup("lobby")

        b.destroy()
        b.destroy()

        assertTrue(b.isDestroyed)
        verify(exactly = 1) { lobbyTopic.removeListener(7) }
    }
}
