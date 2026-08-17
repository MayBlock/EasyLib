package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.messaging.api.MessageType
import com.github.mayblock.easylib.messaging.api.Target
import com.github.mayblock.easylib.messaging.api.subscribe
import com.github.mayblock.easylib.redis.testing.RequiresRedis
import com.github.mayblock.easylib.redis.testing.TestRedisClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@MessageType("com.example.stamped.v1")
data class Stamped(val n: Int, val at: Instant)

/**
 * subscribe 的过滤/投递语义，跑在真实 Redis 上。
 *
 * 需要伪造发送方、类型或畸形 JSON 的用例，直接往频道 publish 原始字符串（[publishRaw]）——
 * 消息经过真实的 Redis Pub/Sub 与 Redisson 监听器进入总线，而不是手工调用监听器回调。
 */
@RequiresRedis
class RedisMessageBusSubscribeTest {

    private val ns = ns()
    private val all = ChannelNames.of(ns, Target.All)

    private suspend fun bus(client: TestRedisClient, id: String = "me") =
        RedisMessageBus.create(client = client, instanceId = id, namespace = ns)

    /** 模拟一条来自 [sender] 的入站消息。 */
    private suspend fun TestRedisClient.deliver(sender: String, type: String, payloadJson: String) =
        publishRaw(all, envelopeJson(sender, type, payloadJson))

    @Test
    fun `收到匹配类型的消息`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            val received = collecting(b) { b.subscribe<Hello>().first() }

            client.deliver("other-instance", "com.example.hello.v1", """{"who":"Steve"}""")

            val env = received.awaitSoon()
            assertEquals(Hello("Steve"), env.payload)
            assertEquals("other-instance", env.senderId)
            assertEquals("i1", env.id)
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `不匹配的类型不投递`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            val received = collecting(b) { b.subscribe<Hello>().take(1).toList() }

            client.deliver("other", "com.example.other.v1", """{"n":1}""")
            client.deliver("other", "com.example.hello.v1", """{"who":"Alex"}""")

            assertEquals(listOf(Hello("Alex")), received.awaitSoon().map { it.payload })
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `默认丢弃自己发出的消息`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            val received = collecting(b) { b.subscribe<Hello>().take(1).toList() }

            client.deliver("me", "com.example.hello.v1", """{"who":"self"}""")
            client.deliver("peer", "com.example.hello.v1", """{"who":"peer"}""")

            assertEquals(listOf(Hello("peer")), received.awaitSoon().map { it.payload })
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `includeSelf 为 true 时收到自己发出的消息`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            val received = collecting(b) { b.subscribe<Hello>(includeSelf = true).first() }

            // 真正由自己 publish，而不是伪造 sender——这条走完整的 encode → Redis → decode 链路。
            b.publish(Target.All, Hello("self"))

            assertEquals(Hello("self"), received.awaitSoon().payload)
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `一条坏消息不会打断订阅`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            val received = collecting(b) { b.subscribe<Hello>().take(1).toList() }

            // 畸形 JSON
            client.publishRaw(all, "}{ not json")
            // 类型名匹配但缺必填字段
            client.deliver("peer", "com.example.hello.v1", """{}""")
            // 正常消息——订阅必须还活着
            client.deliver("peer", "com.example.hello.v1", """{"who":"survivor"}""")

            assertEquals(listOf(Hello("survivor")), received.awaitSoon().map { it.payload })
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `订阅未标注解的类型立即抛异常`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            assertFailsWith<IllegalArgumentException> { b.subscribe(NotAMessage::class.java) }
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `已在 collect 的订阅方能收到 joinGroup 之后新群组频道的消息`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            // collector 必须先挂上——inbound 是 replay = 0 的 SharedFlow，晚到的订阅方收不到早发的消息。
            val received = collecting(b) { b.subscribe<Hello>().first() }

            // 加入群组发生在 collector 已经在跑之后：不应该需要重新订阅。
            b.joinGroup("lobby")

            client.publishRaw(
                ChannelNames.of(ns, Target.Group("lobby")),
                envelopeJson("peer", "com.example.hello.v1", """{"who":"FromGroup"}"""),
            )

            assertEquals(Hello("FromGroup"), received.awaitSoon().payload)
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `广播消息送达另一实例并且不回灌给自己`(client: TestRedisClient) = runBlocking {
        val a = bus(client, "a")
        val b = bus(client, "b")
        try {
            val onB = collecting(b) { b.subscribe<Stamped>().first() }
            val onA = collecting(a) { a.subscribe<Stamped>().first() }
            val at = Instant.parse("2026-08-17T00:00:00.123Z")

            a.publish(Target.All, Stamped(1, at))

            val env = onB.awaitSoon()
            assertEquals(Stamped(1, at), env.payload)
            assertEquals("a", env.senderId)
            assertNull(onA.awaitNothing(), "发送方自己不应收到未开启 includeSelf 的消息")
        } finally {
            a.destroy(); b.destroy()
        }
    }

    @Test
    fun `Instance 目标只送达对应实例`(client: TestRedisClient) = runBlocking {
        val a = bus(client, "a")
        val b = bus(client, "b")
        val c = bus(client, "c")
        try {
            val onB = collecting(b) { b.subscribe<Ping>().first() }
            val onC = collecting(c) { c.subscribe<Ping>().first() }

            a.publish(Target.Instance("b"), Ping("for-b"))

            assertEquals("for-b", onB.awaitSoon().payload.note)
            assertNull(onC.awaitNothing(), "非目标实例不应收到点对点消息")
        } finally {
            a.destroy(); b.destroy(); c.destroy()
        }
    }

    @Test
    fun `多条消息按发布顺序到达`(client: TestRedisClient) = runBlocking {
        val a = bus(client, "a")
        val b = bus(client, "b")
        try {
            val onB = collecting(b) { b.subscribe<Stamped>().take(20).toList() }

            repeat(20) { a.publish(Target.All, Stamped(it, Instant.EPOCH)) }

            assertEquals((0 until 20).toList(), onB.awaitSoon().map { it.payload.n })
        } finally {
            a.destroy(); b.destroy()
        }
    }
}
