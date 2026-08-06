package com.github.mayblock.easylib.messaging.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import com.github.mayblock.easylib.messaging.api.MessageType
import com.github.mayblock.easylib.messaging.api.Target
import com.github.mayblock.easylib.redis.RedisClient
import io.mockk.CapturingSlot
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

@MessageType("com.example.ping.v1")
data class Ping(val note: String)

data class NotAMessage(val v: Int = 0)

class RedisMessageBusPublishTest {

    private class TestClient(
        override val redisson: RedissonClient,
        override val metrics: MetricsRecorder = NoOpMetricsRecorder,
    ) : RedisClient()

    private val topics = mutableMapOf<String, RTopic>()
    private val redisson = mockk<RedissonClient>(relaxed = true).also {
        every { it.isShutdown } returns false
        every { it.isShuttingDown } returns false
    }

    /** 记录某频道被 publish 的原始 JSON。 */
    private fun topicFor(channel: String): Pair<RTopic, CapturingSlot<Any>> {
        val topic = mockk<RTopic>(relaxed = true)
        val payload = slot<Any>()
        every { topic.publishAsync(capture(payload)) } returns CompletableFutureWrapper(1L)
        every { topic.addListenerAsync(String::class.java, any()) } returns CompletableFutureWrapper(1)
        every { redisson.getTopic(channel, any<Codec>()) } returns topic
        topics[channel] = topic
        return topic to payload
    }

    /** 与 [topicFor] 类似，但额外捕获注册到该频道的 [MessageListener]，供测试直接驱动。 */
    private fun topicWithListener(channel: String): Pair<RTopic, CapturingSlot<MessageListener<String>>> {
        val topic = mockk<RTopic>(relaxed = true)
        val listenerSlot = slot<MessageListener<String>>()
        every {
            topic.addListenerAsync(String::class.java, capture(listenerSlot))
        } returns CompletableFutureWrapper(1)
        every { redisson.getTopic(channel, any<Codec>()) } returns topic
        topics[channel] = topic
        return topic to listenerSlot
    }

    private suspend fun bus() = RedisMessageBus.create(
        client = TestClient(redisson),
        instanceId = "bedwars-3",
        namespace = "easylib",
    )

    @Test
    fun `publish 写到正确的频道`() = runTest {
        topicFor("easylib:msg:all")
        topicFor("easylib:msg:inst:bedwars-3")
        val (groupTopic, _) = topicFor("easylib:msg:group:lobby")

        bus().publish(Target.Group("lobby"), Ping("hi"))

        verify(exactly = 1) { groupTopic.publishAsync(any()) }
    }

    @Test
    fun `publish 的 JSON 含正确的 sender type 与 payload`() = runTest {
        topicFor("easylib:msg:inst:bedwars-3")
        val (_, captured) = topicFor("easylib:msg:all")

        bus().publish(Target.All, Ping("hi"))

        val node = ObjectMapper().readTree(captured.captured as String)
        assertEquals("bedwars-3", node.get("sender").asText())
        assertEquals("com.example.ping.v1", node.get("type").asText())
        assertEquals("hi", node.get("payload").get("note").asText())
    }

    @Test
    fun `publish 未标注解的消息抛 IllegalArgumentException`() = runTest {
        topicFor("easylib:msg:all")
        topicFor("easylib:msg:inst:bedwars-3")

        assertFailsWith<IllegalArgumentException> {
            bus().publish(Target.All, NotAMessage())
        }
    }

    @Test
    fun `create 订阅 all 与本实例两个频道`() = runTest {
        val (allTopic, _) = topicFor("easylib:msg:all")
        val (instTopic, _) = topicFor("easylib:msg:inst:bedwars-3")

        bus()

        verify(exactly = 1) { allTopic.addListenerAsync(String::class.java, any()) }
        verify(exactly = 1) { instTopic.addListenerAsync(String::class.java, any()) }
    }

    @Test
    fun `initialGroups 在 create 时即订阅`() = runTest {
        topicFor("easylib:msg:all")
        topicFor("easylib:msg:inst:bedwars-3")
        val (groupTopic, _) = topicFor("easylib:msg:group:lobby")

        val b = RedisMessageBus.create(
            client = TestClient(redisson),
            instanceId = "bedwars-3",
            namespace = "easylib",
            initialGroups = setOf("lobby"),
        )

        verify(exactly = 1) { groupTopic.addListenerAsync(String::class.java, any()) }
        assertEquals(setOf("lobby"), b.groups)
    }

    @Test
    fun `joinGroup 注册监听器并更新 groups`() = runTest {
        topicFor("easylib:msg:all")
        topicFor("easylib:msg:inst:bedwars-3")
        val (groupTopic, _) = topicFor("easylib:msg:group:lobby")

        val b = bus()
        assertEquals(emptySet(), b.groups)

        b.joinGroup("lobby")

        verify(exactly = 1) { groupTopic.addListenerAsync(String::class.java, any()) }
        assertEquals(setOf("lobby"), b.groups)
    }

    @Test
    fun `joinGroup 幂等——重复加入不重复注册`() = runTest {
        topicFor("easylib:msg:all")
        topicFor("easylib:msg:inst:bedwars-3")
        val (groupTopic, _) = topicFor("easylib:msg:group:lobby")

        val b = bus()
        b.joinGroup("lobby")
        b.joinGroup("lobby")

        verify(exactly = 1) { groupTopic.addListenerAsync(String::class.java, any()) }
        assertEquals(setOf("lobby"), b.groups)
    }

    @Test
    fun `合法信封经监听器进入 inbound flow`() = runTest {
        topicFor("easylib:msg:inst:bedwars-3")
        val (_, listenerSlot) = topicWithListener("easylib:msg:all")

        val b = bus()
        val received = async { b.inbound.first() }
        yield() // 让 collector 先挂上，replay = 0 的 SharedFlow 不等迟到的订阅方

        val json = """
            {"id":"m1","sender":"other","type":"com.example.ping.v1",
             "time":"2024-01-01T00:00:00Z","payload":{"note":"hi"}}
        """.trimIndent()
        listenerSlot.captured.onMessage("easylib:msg:all", json)

        val wire = received.await()
        assertEquals("m1", wire.id)
        assertEquals("other", wire.sender)
        assertEquals("com.example.ping.v1", wire.type)
    }

    /**
     * 这里用 `null` 而不是「语法非法的 JSON 字符串」作为畸形输入：[MessageCodec.decodeEnvelope]
     * 自己已经把 JSON 语法错误、字段缺失等 `Exception` 全部吞掉并返回 null（见其内部
     * `catch (e: Exception)`），所以那类输入根本走不到 [RedisMessageBus] 这层的 catch——用它
     * 做判别测试，删掉 catch 也不会让测试变红，起不到验证作用。
     *
     * `null` 触发的是 Kotlin 为 `decodeEnvelope(json: String)` 生成的参数非空检查
     * （`Intrinsics.checkNotNullParameter`），这段检查在方法体（含其内部 try）执行之前抛出
     * `NullPointerException`，因此 decodeEnvelope 自己的 catch 抓不到它——只有本文件监听器
     * lambda 外层的 catch 能拦下来。这是一个真实场景：Redisson 的 String codec 在某些情况下
     * （如反序列化结果为 tombstone/空消息）可能把 null 交给监听器。
     */
    @Test
    fun `畸形（null）消息体不会让 onMessage 抛出异常，也不会进入 inbound flow`() = runTest {
        topicFor("easylib:msg:inst:bedwars-3")
        val (_, listenerSlot) = topicWithListener("easylib:msg:all")

        val b = bus()
        val received = async { withTimeoutOrNull(50) { b.inbound.first() } }
        yield()

        var threw = false
        try {
            listenerSlot.captured.onMessage("easylib:msg:all", null)
        } catch (e: Throwable) {
            threw = true
        }

        assertEquals(false, threw, "onMessage must not let exceptions escape the Netty thread")
        assertEquals(null, received.await(), "malformed body must not reach the inbound flow")
    }

    @Test
    fun `畸形（null）消息之后监听器仍能正常投递后续消息`() = runTest {
        topicFor("easylib:msg:inst:bedwars-3")
        val (_, listenerSlot) = topicWithListener("easylib:msg:all")

        val b = bus()
        val received = async { b.inbound.first() }
        yield()

        listenerSlot.captured.onMessage("easylib:msg:all", null)
        yield()

        val json = """
            {"id":"m2","sender":"other","type":"com.example.ping.v1",
             "time":"2024-01-01T00:00:00Z","payload":{"note":"hi"}}
        """.trimIndent()
        listenerSlot.captured.onMessage("easylib:msg:all", json)

        val wire = received.await()
        assertEquals("m2", wire.id)
    }
}
