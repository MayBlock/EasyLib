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

    // 每个频道各有自己的 mock 与各不相同的 listener id，这样「注销了全部监听器」是可断言的，
    // 而不是只验证其中一个。id 由本文件的 addListenerAsync 打桩决定，因此是已知值——
    // 用具体 id 打桩/验证，避开 MockK 对 Integer... 可变参数匹配器的坑。
    private val allTopic = mockk<RTopic>(relaxed = true)
    private val instTopic = mockk<RTopic>(relaxed = true)
    private val lobbyTopic = mockk<RTopic>(relaxed = true)

    private fun wireUp() {
        every { allTopic.addListenerAsync(String::class.java, any()) } returns CompletableFutureWrapper(1)
        every { redisson.getTopic("easylib:msg:all", any<Codec>()) } returns allTopic

        every { instTopic.addListenerAsync(String::class.java, any()) } returns CompletableFutureWrapper(2)
        every { redisson.getTopic("easylib:msg:inst:me", any<Codec>()) } returns instTopic

        every { lobbyTopic.addListenerAsync(String::class.java, any()) } returns CompletableFutureWrapper(7)
        every { lobbyTopic.removeListenerAsync(7) } returns CompletableFutureWrapper.completedNull()
        every { redisson.getTopic("easylib:msg:group:lobby", any<Codec>()) } returns lobbyTopic
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
    fun `destroy 注销全部三个频道的监听器且幂等`() = runTest {
        wireUp()
        val b = bus()
        b.joinGroup("lobby")

        b.destroy()
        b.destroy()

        assertTrue(b.isDestroyed)
        // 「全部」：create 订的 all 与 inst 两个，加上 joinGroup 订的 lobby。
        // 「幂等」：两次 destroy 之后每个仍然只被注销一次。
        verify(exactly = 1) { allTopic.removeListener(1) }
        verify(exactly = 1) { instTopic.removeListener(2) }
        verify(exactly = 1) { lobbyTopic.removeListener(7) }
    }

    @Test
    fun `destroy 后 groups 为空`() = runTest {
        wireUp()
        val b = bus()
        b.joinGroup("lobby")
        assertEquals(setOf("lobby"), b.groups)

        b.destroy()

        assertTrue(b.groups.isEmpty())
    }

    @Test
    fun `joinGroup 与 destroy 竞态——await 之后才登记的监听器仍被收回`() = runTest {
        wireUp()
        lateinit var b: RedisMessageBus
        // 制造竞态：让 destroy() 恰好在 addListenerAsync 返回之前跑完。真实场景里这是
        // 另一个线程调用 destroy()——destroy() 不可挂起，拿不到 mutex，所以它能插进
        // addListener 的 await 与随后的登记之间。
        every { lobbyTopic.addListenerAsync(String::class.java, any()) } answers {
            b.destroy()
            CompletableFutureWrapper(7)
        }
        b = bus()

        b.joinGroup("lobby")

        // 这个监听器是在 bus 已声称关停之后才登记的。若不收回，它会永远留在 Redis 上，
        // 而调用方看到的是 isDestroyed == true。
        verify(exactly = 1) { lobbyTopic.removeListener(7) }
        assertTrue(b.isDestroyed)
        assertTrue(b.groups.isEmpty())
    }

    @Test
    fun `destroy 中单个频道注销失败不影响其余频道`() = runTest {
        wireUp()
        // all 频道注销时抛异常——其余两个仍必须被注销，且 destroy 本身不得把异常抛出去。
        every { allTopic.removeListener(1) } throws IllegalStateException("redis down")

        val b = bus()
        b.joinGroup("lobby")

        b.destroy()

        assertTrue(b.isDestroyed)
        verify(exactly = 1) { instTopic.removeListener(2) }
        verify(exactly = 1) { lobbyTopic.removeListener(7) }
    }
}
