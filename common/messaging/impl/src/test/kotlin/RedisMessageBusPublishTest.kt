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
import kotlinx.coroutines.test.runTest
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
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
}
