package com.github.mayblock.easylib.messaging.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.messaging.api.Target
import com.github.mayblock.easylib.redis.testing.RedisOp
import com.github.mayblock.easylib.redis.testing.RedisTestSupport
import com.github.mayblock.easylib.redis.testing.RequiresRedis
import com.github.mayblock.easylib.redis.testing.TestRedisClient
import com.github.mayblock.easylib.redis.testing.eventually
import com.github.mayblock.easylib.redis.testing.subscriberCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * publish 与监听器注册相关的行为，跑在真实 Redis 上：频道对不对、JSON 长什么样，由挂在
 * Redis 频道上的原始监听器亲眼看到；「订阅了哪些频道」直接问 Redis（`PUBSUB NUMSUB`）。
 */
@RequiresRedis
class RedisMessageBusPublishTest {

    /** 记录所有经过 recordSuspending 的指标名，用于断言 I/O 路径确实被计量。 */
    private class RecordingMetrics : MetricsRecorder {
        val names = mutableListOf<String>()

        override fun <T> record(name: String, block: () -> T): T {
            names += name
            return block()
        }

        override suspend fun <T> recordSuspending(name: String, block: suspend () -> T): T {
            names += name
            return block()
        }
    }

    private val ns = ns()
    private val all = ChannelNames.of(ns, Target.All)
    private val inst = ChannelNames.of(ns, Target.Instance("bedwars-3"))
    private val lobby = ChannelNames.of(ns, Target.Group("lobby"))

    private suspend fun bus(client: TestRedisClient, initialGroups: Set<String> = emptySet()) =
        RedisMessageBus.create(
            client = client,
            instanceId = "bedwars-3",
            namespace = ns,
            initialGroups = initialGroups,
        )

    @Test
    fun `publish 写到正确的频道`(client: TestRedisClient) = runBlocking {
        val onLobby = client.observe(lobby)
        val onAll = client.observe(all)
        val b = bus(client)
        try {
            b.publish(Target.Group("lobby"), Ping("hi"))

            eventually { onLobby.size == 1 }
            delay(200)
            assertTrue(onAll.isEmpty(), "群组消息不应出现在 all 频道")
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `publish 的 JSON 含正确的 sender type 与 payload`(client: TestRedisClient) = runBlocking {
        val onAll = client.observe(all)
        val b = bus(client)
        try {
            b.publish(Target.All, Ping("hi"))

            eventually { onAll.size == 1 }
            val node = ObjectMapper().readTree(onAll.single())
            assertEquals("bedwars-3", node.get("sender").asText())
            assertEquals("com.example.ping.v1", node.get("type").asText())
            assertEquals("hi", node.get("payload").get("note").asText())
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `publish 失败直接上抛，不重试`(client: TestRedisClient) = runBlocking {
        val g = ChannelNames.of(ns, Target.Group("g"))
        client.hooks.failAlways(g, RedisOp.PUBLISH)
        val b = bus(client)
        try {
            assertFailsWith<RuntimeException> { b.publish(Target.Group("g"), Ping("hi")) }
            // 至多一次投递：PUBLISH 已送达但响应丢失的场景下，重试会把同一条消息（同一
            // envelope id）重复扇出给所有订阅方。本总线的契约是「允许丢失」而非「允许重复」，
            // 所以发送失败不重试，一次尝试后原样上抛。
            assertEquals(1, client.hooks.calls(g, RedisOp.PUBLISH))
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `publish 未标注解的消息抛 IllegalArgumentException`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            assertFailsWith<IllegalArgumentException> { b.publish(Target.All, NotAMessage()) }
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `destroy 后 publish 抛 IllegalStateException`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        b.destroy()

        assertFailsWith<IllegalStateException> { b.publish(Target.All, Ping("hi")) }
    }

    @Test
    fun `create 订阅 all 与本实例两个频道`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            assertEquals(1L, client.subscriberCount(all))
            assertEquals(1L, client.subscriberCount(inst))
            assertEquals(1, client.hooks.calls(all, RedisOp.ADD_LISTENER))
            assertEquals(1, client.hooks.calls(inst, RedisOp.ADD_LISTENER))
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `initialGroups 在 create 时即订阅`(client: TestRedisClient) = runBlocking {
        val b = bus(client, initialGroups = setOf("lobby"))
        try {
            assertEquals(1L, client.subscriberCount(lobby))
            assertEquals(setOf("lobby"), b.groups)
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `joinGroup 注册监听器并更新 groups`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            assertEquals(emptySet(), b.groups)
            assertEquals(0L, client.subscriberCount(lobby))

            b.joinGroup("lobby")

            assertEquals(1L, client.subscriberCount(lobby))
            assertEquals(setOf("lobby"), b.groups)
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `joinGroup 幂等——重复加入不重复注册`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            b.joinGroup("lobby")
            b.joinGroup("lobby")

            assertEquals(1, client.hooks.calls(lobby, RedisOp.ADD_LISTENER))
            assertEquals(setOf("lobby"), b.groups)
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `监听器注册与注销和 publish 一样发出指标`() = runBlocking {
        val metrics = RecordingMetrics()
        val client = RedisTestSupport.newClient(metrics)
        try {
            val b = bus(client)
            // create 注册 all 与 inst 两个监听器，每次注册 I/O 都应计量——
            // publish 与 cache 模块的每个 Redis 操作都有指标，监听器 I/O 不该是盲区。
            assertEquals(2, metrics.names.count { it == "messaging.listener.add" })

            b.joinGroup("lobby")
            assertEquals(3, metrics.names.count { it == "messaging.listener.add" })

            b.leaveGroup("lobby")
            assertEquals(1, metrics.names.count { it == "messaging.listener.remove" })

            b.publish(Target.All, Ping("hi"))
            assertEquals(1, metrics.names.count { it == "messaging.publish" })
            b.destroy()
        } finally {
            client.destroy()
        }
    }

    @Test
    fun `合法信封经监听器进入 inbound flow`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            val received = collecting(b) { b.inbound.first() }

            client.publishRaw(all, envelopeJson("other", "com.example.ping.v1", """{"note":"hi"}""", id = "m1"))

            val wire = received.awaitSoon()
            assertEquals("m1", wire.id)
            assertEquals("other", wire.sender)
            assertEquals("com.example.ping.v1", wire.type)
        } finally {
            b.destroy()
        }
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
     * lambda 外层的 catch 能拦下来。选它做判别输入不是因为「Redisson 确实会传 null」——那一点
     * 无从证实，也不重要；选它单纯是因为它是唯一能证明能穿透到监听器外层 catch 的输入，而
     * 那层 catch 本来就是给 Netty 回调兜底的纵深防御，值得单独验证它真的接得住。
     *
     * Redis 本身发不出 null，所以这条必须绕过 Redis：从 [TestRedisClient.hooks] 取出真正注册
     * 到 all 频道的那个监听器直接调用。
     */
    @Test
    fun `畸形（null）消息体不会让 onMessage 抛出异常，也不会进入 inbound flow`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            val received = collecting(b) { b.inbound.first() }
            val listener = client.hooks.listenersOn(all).single()

            var threw = false
            try {
                listener.onMessage(all, null)
            } catch (e: Throwable) {
                threw = true
            }

            assertEquals(false, threw, "onMessage must not let exceptions escape the Netty thread")
            assertNull(received.awaitNothing(), "malformed body must not reach the inbound flow")
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `畸形（null）消息之后监听器仍能正常投递后续消息`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            val received = collecting(b) { b.inbound.first() }
            client.hooks.listenersOn(all).single().onMessage(all, null)

            client.publishRaw(all, envelopeJson("other", "com.example.ping.v1", """{"note":"hi"}""", id = "m2"))

            assertEquals("m2", received.awaitSoon().id)
        } finally {
            b.destroy()
        }
    }
}
