package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.messaging.api.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

@MessageType("com.example.move.v1")
data class MoveV1(val player: String)

@MessageType("com.example.move.v2")
data class MoveV2(val player: String, val world: String = "world")

@MessageType("com.example.scheduled.v1")
data class ScheduledEvent(val label: String, val at: java.time.Instant)

class MessageCodecJsonTest {

    private val fixedTime = Instant.parse("2026-08-07T10:23:45.123Z")

    private fun codec() = MessageCodec(
        idGenerator = { "fixed-id" },
        clock = { fixedTime },
    )

    @Test
    fun `encode 产出预期的信封字段`() {
        val json = codec().encode("bedwars-3", MoveV1("Steve"))
        val wire = assertNotNull(codec().decodeEnvelope(json))

        assertEquals("fixed-id", wire.id)
        assertEquals("bedwars-3", wire.sender)
        assertEquals("com.example.move.v1", wire.type)
        assertEquals("2026-08-07T10:23:45.123Z", wire.time)
        assertEquals("Steve", wire.payload.get("player").asText())
    }

    @Test
    fun `encode 到 toEnvelope 的往返保持 payload 与元数据`() {
        val c = codec()
        val wire = assertNotNull(c.decodeEnvelope(c.encode("lobby-1", MoveV1("Alex"))))
        val env = assertNotNull(c.toEnvelope(wire, MoveV1::class.java))

        assertEquals(MoveV1("Alex"), env.payload)
        assertEquals("fixed-id", env.id)
        assertEquals("lobby-1", env.senderId)
        assertEquals(fixedTime, env.time)
    }

    @Test
    fun `未知字段被忽略——新版本发的消息旧类仍能解`() {
        val c = codec()
        val v2 = assertNotNull(c.decodeEnvelope(c.encode("s", MoveV2("Steve", "nether"))))
        // 线上名不同，实际不会投给 MoveV1 的订阅方；这里只验证解码器的宽容性
        val asV1 = c.toEnvelope(v2, MoveV1::class.java)
        assertEquals(MoveV1("Steve"), asV1?.payload)
    }

    @Test
    fun `缺少必填字段时返回 null 而不抛异常`() {
        val c = codec()
        val json = """{"id":"i","sender":"s","type":"com.example.move.v1","time":"2026-08-07T10:23:45.123Z","payload":{}}"""
        val empty = assertNotNull(c.decodeEnvelope(json))
        assertNull(c.toEnvelope(empty, MoveV1::class.java))
    }

    @Test
    fun `新增带默认值的字段无需升版本即可被接收`() {
        val c = codec()
        val json = """{"id":"i","sender":"s","type":"com.example.move.v2","time":"2026-08-07T10:23:45.123Z","payload":{"player":"Steve"}}"""
        val wire = assertNotNull(c.decodeEnvelope(json))
        val env = assertNotNull(c.toEnvelope(wire, MoveV2::class.java))
        assertEquals(MoveV2("Steve", "world"), env.payload)
    }

    @Test
    fun `畸形 JSON 返回 null`() {
        assertNull(codec().decodeEnvelope("not json at all"))
        assertNull(codec().decodeEnvelope("""{"id":"only-id"}"""))
    }

    @Test
    fun `时间戳畸形时返回 null`() {
        val c = codec()
        val json = """{"id":"i","sender":"s","type":"com.example.move.v1","time":"not-a-time","payload":{"player":"Steve"}}"""
        val wire = assertNotNull(c.decodeEnvelope(json))
        assertNull(c.toEnvelope(wire, MoveV1::class.java))
    }

    @Test
    fun `close 后 encode 抛 IllegalStateException`() {
        val c = codec()
        c.close()
        assertFailsWith<IllegalStateException> { c.encode("s", MoveV1("Steve")) }
    }

    @Test
    fun `close 后 decodeEnvelope 与 toEnvelope 返回 null`() {
        val c = codec()
        val json = c.encode("s", MoveV1("Steve"))
        val wire = assertNotNull(c.decodeEnvelope(json))

        c.close()

        // close 断开了对 ObjectMapper 的引用——mapper 的序列化器缓存强引用着
        // 每个处理过的消息类，是 claimedNames 之外的第二条 classloader 泄漏链。
        // 关闭后编解码一律按"坏消息"路径丢弃，与总线 destroy 后不再投递的语义一致。
        assertNull(c.decodeEnvelope(json))
        assertNull(c.toEnvelope(wire, MoveV1::class.java))
    }

    @Test
    fun `payload 中的 java-time Instant 字段完整往返`() {
        val c = codec()
        val at = java.time.Instant.parse("2026-01-02T03:04:05.678Z")
        val json = c.encode("s", ScheduledEvent("launch", at))
        val wire = assertNotNull(c.decodeEnvelope(json))
        val env = assertNotNull(c.toEnvelope(wire, ScheduledEvent::class.java))

        assertEquals(ScheduledEvent("launch", at), env.payload)
    }
}
