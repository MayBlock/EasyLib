package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import com.github.mayblock.easylib.messaging.api.MessageType
import com.github.mayblock.easylib.messaging.api.subscribe
import com.github.mayblock.easylib.redis.RedisClient
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.redisson.api.listener.MessageListener
import org.redisson.client.codec.Codec
import org.redisson.misc.CompletableFutureWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@MessageType("com.example.hello.v1")
data class Hello(val who: String)

data class NoAnnotation(val v: Int = 0)

class RedisMessageBusSubscribeTest {

    private class TestClient(
        override val redisson: RedissonClient,
        override val metrics: MetricsRecorder = NoOpMetricsRecorder,
    ) : RedisClient()

    private val redisson = mockk<RedissonClient>(relaxed = true).also {
        every { it.isShutdown } returns false
        every { it.isShuttingDown } returns false
    }

    /** 捕获注册到 all 频道的监听器，用于手动灌消息。 */
    private val allListener: CapturingSlot<MessageListener<String>> = slot()

    private fun wireUp() {
        val topic = mockk<RTopic>(relaxed = true)
        every { topic.addListenerAsync(String::class.java, capture(allListener)) } returns
            CompletableFutureWrapper(1)
        every { redisson.getTopic("easylib:msg:all", any<Codec>()) } returns topic

        val other = mockk<RTopic>(relaxed = true)
        every { other.addListenerAsync(String::class.java, any()) } returns CompletableFutureWrapper(2)
        every { redisson.getTopic(match { it != "easylib:msg:all" }, any<Codec>()) } returns other
    }

    private suspend fun bus() = RedisMessageBus.create(
        client = TestClient(redisson),
        instanceId = "me",
        namespace = "easylib",
    )

    /** 模拟一条来自 [sender] 的入站消息。 */
    private fun deliver(sender: String, type: String, payloadJson: String) {
        val json = """{"id":"i1","sender":"$sender","type":"$type","time":"2026-08-07T10:00:00Z","payload":$payloadJson}"""
        allListener.captured.onMessage("easylib:msg:all", json)
    }

    @Test
    fun `收到匹配类型的消息`() = runTest {
        wireUp()
        val b = bus()
        val received = async { b.subscribe<Hello>().first() }
        yield()

        deliver("other-instance", "com.example.hello.v1", """{"who":"Steve"}""")

        val env = received.await()
        assertEquals(Hello("Steve"), env.payload)
        assertEquals("other-instance", env.senderId)
        assertEquals("i1", env.id)
    }

    @Test
    fun `不匹配的类型不投递`() = runTest {
        wireUp()
        val b = bus()
        val received = async { b.subscribe<Hello>().take(1).toList() }
        yield()

        deliver("other", "com.example.other.v1", """{"n":1}""")
        deliver("other", "com.example.hello.v1", """{"who":"Alex"}""")

        assertEquals(listOf(Hello("Alex")), received.await().map { it.payload })
    }

    @Test
    fun `默认丢弃自己发出的消息`() = runTest {
        wireUp()
        val b = bus()
        val received = async { b.subscribe<Hello>().take(1).toList() }
        yield()

        deliver("me", "com.example.hello.v1", """{"who":"self"}""")
        deliver("peer", "com.example.hello.v1", """{"who":"peer"}""")

        assertEquals(listOf(Hello("peer")), received.await().map { it.payload })
    }

    @Test
    fun `includeSelf 为 true 时收到自己发出的消息`() = runTest {
        wireUp()
        val b = bus()
        val received = async { b.subscribe<Hello>(includeSelf = true).first() }
        yield()

        deliver("me", "com.example.hello.v1", """{"who":"self"}""")

        assertEquals(Hello("self"), received.await().payload)
    }

    @Test
    fun `一条坏消息不会打断订阅`() = runTest {
        wireUp()
        val b = bus()
        val received = async { b.subscribe<Hello>().take(1).toList() }
        yield()

        // 畸形 JSON
        allListener.captured.onMessage("easylib:msg:all", "}{ not json")
        // 类型名匹配但缺必填字段
        deliver("peer", "com.example.hello.v1", """{}""")
        // 正常消息——订阅必须还活着
        deliver("peer", "com.example.hello.v1", """{"who":"survivor"}""")

        assertEquals(listOf(Hello("survivor")), received.await().map { it.payload })
    }

    @Test
    fun `订阅未标注解的类型立即抛异常`() = runTest {
        wireUp()
        val b = bus()
        assertFailsWith<IllegalArgumentException> { b.subscribe(NoAnnotation::class) }
    }

    @Test
    fun `已在 collect 的订阅方能收到 joinGroup 之后新群组频道的消息`() = runTest {
        wireUp()
        // 群组频道在 wireUp() 之后单独 stub，覆盖掉那里"any != all"的兜底 mock。
        val groupTopic = mockk<RTopic>(relaxed = true)
        val groupListener: CapturingSlot<MessageListener<String>> = slot()
        every { groupTopic.addListenerAsync(String::class.java, capture(groupListener)) } returns
            CompletableFutureWrapper(2)
        every { redisson.getTopic("easylib:msg:group:lobby", any<Codec>()) } returns groupTopic

        val b = bus()
        // collector 必须先挂上——inbound 是 replay = 0 的 SharedFlow，晚到的订阅方收不到早发的消息。
        val received = async { b.subscribe<Hello>().first() }
        yield()

        // 加入群组发生在 collector 已经在跑之后：不应该需要重新订阅。
        b.joinGroup("lobby")

        val json = """{"id":"i1","sender":"peer","type":"com.example.hello.v1","time":"2026-08-07T10:00:00Z","payload":{"who":"FromGroup"}}"""
        groupListener.captured.onMessage("easylib:msg:group:lobby", json)

        assertEquals(Hello("FromGroup"), received.await().payload)
    }
}
