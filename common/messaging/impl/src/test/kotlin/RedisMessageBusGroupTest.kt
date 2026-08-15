package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import com.github.mayblock.easylib.redis.RedisClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.redisson.api.listener.MessageListener
import org.redisson.client.codec.Codec
import org.redisson.misc.CompletableFutureWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
        every { allTopic.removeListenerAsync(1) } returns CompletableFutureWrapper.completedNull()
        every { redisson.getTopic("easylib:msg:all", any<Codec>()) } returns allTopic

        every { instTopic.addListenerAsync(String::class.java, any()) } returns CompletableFutureWrapper(2)
        every { instTopic.removeListenerAsync(2) } returns CompletableFutureWrapper.completedNull()
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
        // 注销走异步 API 并行发出——同步逐个等待会让主线程上的 destroy 在 Redis
        // 不可达时停顿「频道数 × 命令超时」之久。
        verify(exactly = 1) { allTopic.removeListenerAsync(1) }
        verify(exactly = 1) { instTopic.removeListenerAsync(2) }
        verify(exactly = 1) { lobbyTopic.removeListenerAsync(7) }
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
        verify(exactly = 1) { lobbyTopic.removeListenerAsync(7) }
        assertTrue(b.isDestroyed)
        assertTrue(b.groups.isEmpty())
    }

    @Test
    fun `destroy 后 joinGroup 抛 IllegalStateException 且 groups 仍为空`() = runTest {
        wireUp()
        val b = bus()
        b.destroy()

        assertFailsWith<IllegalStateException> { b.joinGroup("lobby") }
        assertTrue(b.groups.isEmpty())
    }

    @Test
    fun `leaveGroup 的 I-O 失败不摘除监听器记录，重试后能干净收尾且不重复注册`() = runTest {
        wireUp()
        var attempts = 0
        every { lobbyTopic.removeListenerAsync(7) } answers {
            attempts++
            if (attempts == 1) throw RuntimeException("redis down") else CompletableFutureWrapper.completedNull()
        }
        val b = bus()
        b.joinGroup("lobby")

        assertFailsWith<RuntimeException> { b.leaveGroup("lobby") }
        // I/O 失败：listeners 与 joined 都必须保持原状，而不是提前声称已经离开。
        assertEquals(setOf("lobby"), b.groups)

        b.leaveGroup("lobby")

        assertTrue(b.groups.isEmpty())
        verify(exactly = 2) { lobbyTopic.removeListenerAsync(7) }
        // 期间没有第二次 addListenerAsync——也就没有在同一频道上注册出第二个监听器。
        verify(exactly = 1) { lobbyTopic.addListenerAsync(String::class.java, any()) }
    }

    @Test
    fun `joinGroup 失败时回滚 joined，修复后重试可以成功`() = runTest {
        wireUp()
        var attempts = 0
        every { lobbyTopic.addListenerAsync(String::class.java, any()) } answers {
            attempts++
            if (attempts == 1) throw RuntimeException("redis down") else CompletableFutureWrapper(7)
        }
        val b = bus()

        assertFailsWith<RuntimeException> { b.joinGroup("lobby") }
        // 注册失败：joined 不能残留这个名字，否则幂等检查会让重试变成静默空操作。
        assertTrue(b.groups.isEmpty())

        b.joinGroup("lobby")

        assertEquals(setOf("lobby"), b.groups)
    }

    @Test
    fun `create 失败时回收已注册的监听器`() = runTest {
        wireUp()
        every { instTopic.addListenerAsync(String::class.java, any()) } throws RuntimeException("redis down")

        assertFailsWith<RuntimeException> { bus() }

        // all 频道先于 inst 频道注册，成功登记了 id 1——create 失败后这个监听器不能
        // 永远留在 Redis 上没人认领。
        verify(exactly = 1) { allTopic.removeListenerAsync(1) }
    }

    @Test
    fun `destroy 中单个频道注销失败不影响其余频道`() = runTest {
        wireUp()
        // all 频道注销时抛异常——其余两个仍必须被注销，且 destroy 本身不得把异常抛出去。
        every { allTopic.removeListenerAsync(1) } throws IllegalStateException("redis down")

        val b = bus()
        b.joinGroup("lobby")

        b.destroy()

        assertTrue(b.isDestroyed)
        verify(exactly = 1) { instTopic.removeListenerAsync(2) }
        verify(exactly = 1) { lobbyTopic.removeListenerAsync(7) }
    }

    @Test
    fun `destroy 置位后残存监听器立即停止投递，不等注销完成`() = runTest {
        wireUp()
        val lateJson =
            """{"id":"i1","sender":"peer","type":"com.example.hello.v1","time":"2026-08-07T10:00:00Z","payload":{"who":"late"}}"""
        val listenerSlot = slot<MessageListener<String>>()
        every { allTopic.addListenerAsync(String::class.java, capture(listenerSlot)) } returns
            CompletableFutureWrapper(1)
        // 注销 all 频道的 I/O 尚未完成时，一条消息恰好到达该监听器；且注销最终失败，
        // 监听器残留在 Redis 上（destroy 对此只记 warn）。
        every { allTopic.removeListenerAsync(1) } answers {
            listenerSlot.captured.onMessage("easylib:msg:all", lateJson)
            throw IllegalStateException("redis down")
        }

        val b = bus()
        val received = async { withTimeoutOrNull(50) { b.inbound.first() } }
        yield()

        b.destroy()
        // 注销失败后监听器仍活着——重连后又送来一条。
        listenerSlot.captured.onMessage("easylib:msg:all", lateJson)

        // destroy 的契约是「入站消息不再到达任何订阅方」，它不能依赖注销是否成功、
        // 也不能等注销 I/O 做完才生效——destroyed 置位即是闸门。
        assertNull(received.await(), "destroyed bus must not deliver inbound messages")
    }

    @Test
    fun `destroy 后 subscribe 抛 IllegalStateException`() = runTest {
        wireUp()
        val b = bus()
        b.destroy()

        // 返回一个永不产出的 Flow 会被误读成「没有消息」而非「总线已关停」；而且
        // subscribe 会调 wireNameOf，把消息类重新登记进 codec 的注册表、再次钉住
        // 本该被回收的 classloader。
        assertFailsWith<IllegalStateException> { b.subscribe(Hello::class.java) }
    }

    @Test
    fun `destroy 清空线上名注册表以释放 classloader`() = runTest {
        wireUp()
        val b = bus()
        // 先让 Hello 登记进注册表。
        b.subscribe(Hello::class.java)

        b.destroy()

        // close 之后注册表已清空、且解析不再写回：一个「声明了同一线上名的不同类」不应
        // 再被判为冲突——若表未清空（或这次解析又把类登记了回去），下面会因 Hello 仍占着
        // com.example.hello.v1 而抛异常。这是从外部观察「强引用是否已断开」的唯一手段：
        // Class 引用本身不可直接断言。
        assertEquals("com.example.hello.v1", codecOf(b).wireNameOf(HelloClash::class.java))
        assertEquals("com.example.hello.v1", codecOf(b).wireNameOf(HelloClash::class.java))
    }
}

/** 与 `Hello`（定义在 RedisMessageBusSubscribeTest.kt）声明了同一线上名，用于探测注册表状态。 */
@com.github.mayblock.easylib.messaging.api.MessageType("com.example.hello.v1")
private data class HelloClash(val who: String = "")

/** 读出 bus 内部的 codec——注册表是否清空只能这样观察。 */
private fun codecOf(bus: RedisMessageBus): MessageCodec {
    val f = RedisMessageBus::class.java.getDeclaredField("codec")
    f.isAccessible = true
    return f.get(bus) as MessageCodec
}
