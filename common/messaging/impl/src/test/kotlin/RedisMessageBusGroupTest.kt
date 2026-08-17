package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.messaging.api.MessageType
import com.github.mayblock.easylib.messaging.api.Target
import com.github.mayblock.easylib.messaging.api.subscribe
import com.github.mayblock.easylib.redis.testing.RedisOp
import com.github.mayblock.easylib.redis.testing.RequiresRedis
import com.github.mayblock.easylib.redis.testing.TestRedisClient
import com.github.mayblock.easylib.redis.testing.eventually
import com.github.mayblock.easylib.redis.testing.subscriberCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 群组生命周期与 destroy，跑在真实 Redis 上。
 *
 * 「监听器是否还在 Redis 上」直接问 Redis（`PUBSUB NUMSUB`）；I/O 故障由 [TestRedisClient.hooks]
 * 在真实调用发出**之前**注入——注册失败时 Redis 上确实没有监听器，注销失败时监听器确实残留在
 * Redis 上，被测代码面对的是真实的故障后状态。
 */
@RequiresRedis
class RedisMessageBusGroupTest {

    private val ns = ns()
    private val all = ChannelNames.of(ns, Target.All)
    private val inst = ChannelNames.of(ns, Target.Instance("me"))
    private val lobby = ChannelNames.of(ns, Target.Group("lobby"))

    private suspend fun bus(client: TestRedisClient, id: String = "me") =
        RedisMessageBus.create(client = client, instanceId = id, namespace = ns)

    @Test
    fun `加入群组后收到群组消息，leaveGroup 注销监听器后不再收到`(client: TestRedisClient) = runBlocking {
        val a = bus(client, "a")
        val b = bus(client)
        try {
            b.joinGroup("lobby")
            val onB = collecting(b) { b.subscribe<Ping>().first() }
            a.publish(Target.Group("lobby"), Ping("in"))
            assertEquals("in", onB.awaitSoon().payload.note)

            b.leaveGroup("lobby")

            assertTrue(b.groups.isEmpty())
            assertEquals(1, client.hooks.calls(lobby, RedisOp.REMOVE_LISTENER))
            eventually { client.subscriberCount(lobby) == 0L }
            val afterLeave = collecting(b) { b.subscribe<Ping>().first() }
            a.publish(Target.Group("lobby"), Ping("out"))
            assertNull(afterLeave.awaitNothing(), "离开群组后不应再收到该群组的消息")
        } finally {
            a.destroy(); b.destroy()
        }
    }

    @Test
    fun `leaveGroup 对未加入的群组是空操作`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            b.leaveGroup("never-joined")

            assertEquals(0, client.hooks.calls(ChannelNames.of(ns, Target.Group("never-joined")), RedisOp.REMOVE_LISTENER))
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `groups 返回快照——之后的 leave 不影响已取出的集合`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        try {
            b.joinGroup("lobby")
            val snapshot = b.groups

            b.leaveGroup("lobby")

            assertEquals(setOf("lobby"), snapshot)
            assertTrue(b.groups.isEmpty())
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `destroy 注销全部三个频道的监听器、清空 groups 且幂等`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        b.joinGroup("lobby")
        assertEquals(setOf("lobby"), b.groups)

        b.destroy()
        b.destroy()

        assertTrue(b.isDestroyed)
        assertTrue(b.groups.isEmpty())
        // 「全部」：create 订的 all 与 inst 两个，加上 joinGroup 订的 lobby——Redis 上一个都不剩。
        eventually { client.subscriberCount(all) == 0L }
        eventually { client.subscriberCount(inst) == 0L }
        eventually { client.subscriberCount(lobby) == 0L }
        // 「幂等」：两次 destroy 之后每个仍然只被注销一次。
        assertEquals(1, client.hooks.calls(all, RedisOp.REMOVE_LISTENER))
        assertEquals(1, client.hooks.calls(inst, RedisOp.REMOVE_LISTENER))
        assertEquals(1, client.hooks.calls(lobby, RedisOp.REMOVE_LISTENER))
    }

    @Test
    fun `joinGroup 与 destroy 竞态——await 之后才登记的监听器仍被收回`(client: TestRedisClient) = runBlocking {
        lateinit var b: RedisMessageBus
        // 制造竞态：让 destroy() 恰好在 lobby 的 addListenerAsync 发出之前跑完。真实场景里这是
        // 另一个线程调用 destroy()——destroy() 不可挂起，拿不到 mutex，所以它能插进
        // addListener 的 await 与随后的登记之间。
        client.hooks.before { name, op -> if (name == lobby && op == RedisOp.ADD_LISTENER) b.destroy() }
        b = bus(client)

        b.joinGroup("lobby")

        // 这个监听器是在 bus 已声称关停之后才登记的。若不收回，它会永远留在 Redis 上，
        // 而调用方看到的是 isDestroyed == true。
        assertEquals(1, client.hooks.calls(lobby, RedisOp.REMOVE_LISTENER))
        eventually { client.subscriberCount(lobby) == 0L }
        assertTrue(b.isDestroyed)
        assertTrue(b.groups.isEmpty())
    }

    @Test
    fun `destroy 后 joinGroup 抛 IllegalStateException 且 groups 仍为空`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        b.destroy()

        assertFailsWith<IllegalStateException> { b.joinGroup("lobby") }
        assertTrue(b.groups.isEmpty())
        assertEquals(0L, client.subscriberCount(lobby))
    }

    @Test
    fun `leaveGroup 的 I-O 失败不摘除监听器记录，重试后能干净收尾且不重复注册`(client: TestRedisClient) = runBlocking {
        client.hooks.failOnce(lobby, RedisOp.REMOVE_LISTENER)
        val b = bus(client)
        try {
            b.joinGroup("lobby")

            assertFailsWith<RuntimeException> { b.leaveGroup("lobby") }
            // I/O 失败：监听器仍然真的挂在 Redis 上，listeners 表必须如实保留，不能提前声称已经离开。
            assertEquals(setOf("lobby"), b.groups)
            assertEquals(1L, client.subscriberCount(lobby))

            b.leaveGroup("lobby")

            assertTrue(b.groups.isEmpty())
            eventually { client.subscriberCount(lobby) == 0L }
            assertEquals(2, client.hooks.calls(lobby, RedisOp.REMOVE_LISTENER))
            // 期间没有第二次 addListenerAsync——也就没有在同一频道上注册出第二个监听器。
            assertEquals(1, client.hooks.calls(lobby, RedisOp.ADD_LISTENER))
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `joinGroup 失败时回滚，修复后重试可以成功`(client: TestRedisClient) = runBlocking {
        client.hooks.failOnce(lobby, RedisOp.ADD_LISTENER)
        val b = bus(client)
        try {
            assertFailsWith<RuntimeException> { b.joinGroup("lobby") }
            // 注册失败：groups 不能残留这个名字，否则幂等检查会让重试变成静默空操作。
            assertTrue(b.groups.isEmpty())
            assertEquals(0L, client.subscriberCount(lobby))

            b.joinGroup("lobby")

            assertEquals(setOf("lobby"), b.groups)
            assertEquals(1L, client.subscriberCount(lobby))
        } finally {
            b.destroy()
        }
    }

    @Test
    fun `create 失败时回收已注册的监听器`(client: TestRedisClient) = runBlocking {
        client.hooks.failAlways(inst, RedisOp.ADD_LISTENER)

        assertFailsWith<RuntimeException> { bus(client) }

        // all 频道先于 inst 频道注册成功——create 失败后这个监听器不能永远留在 Redis 上没人认领。
        assertEquals(1, client.hooks.calls(all, RedisOp.REMOVE_LISTENER))
        eventually { client.subscriberCount(all) == 0L }
    }

    @Test
    fun `destroy 中单个频道注销失败不影响其余频道`(client: TestRedisClient) = runBlocking {
        // all 频道注销时抛异常——其余两个仍必须被注销，且 destroy 本身不得把异常抛出去。
        client.hooks.failAlways(all, RedisOp.REMOVE_LISTENER) { IllegalStateException("redis down") }
        val b = bus(client)
        b.joinGroup("lobby")

        b.destroy()

        assertTrue(b.isDestroyed)
        eventually { client.subscriberCount(inst) == 0L }
        eventually { client.subscriberCount(lobby) == 0L }
        // 注销失败的那个真的残留在 Redis 上——这正是下一条用例要面对的状态。
        assertEquals(1L, client.subscriberCount(all))
    }

    @Test
    fun `destroy 置位后残存监听器立即停止投递，不等注销完成`(client: TestRedisClient) = runBlocking {
        val lateJson = envelopeJson("peer", "com.example.hello.v1", """{"who":"late"}""")
        // 注销 all 频道的 I/O 尚未完成时，一条消息恰好到达该监听器；且注销最终失败，
        // 监听器残留在 Redis 上（destroy 对此只记 warn）。
        client.hooks.before { name, op ->
            if (name == all && op == RedisOp.REMOVE_LISTENER) {
                client.hooks.listenersOn(all).single().onMessage(all, lateJson)
                throw IllegalStateException("redis down")
            }
        }
        val b = bus(client)
        val received = collecting(b) { b.inbound.first() }

        b.destroy()
        // 注销失败后监听器仍真的挂在 Redis 上——重连后 Redis 又送来一条。
        assertEquals(1L, client.subscriberCount(all))
        client.publishRaw(all, lateJson)

        // destroy 的契约是「入站消息不再到达任何订阅方」，它不能依赖注销是否成功、
        // 也不能等注销 I/O 做完才生效——destroyed 置位即是闸门。
        assertNull(received.awaitNothing(), "destroyed bus must not deliver inbound messages")
    }

    @Test
    fun `destroy 后 subscribe 抛 IllegalStateException`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
        b.destroy()

        // 返回一个永不产出的 Flow 会被误读成「没有消息」而非「总线已关停」；而且
        // subscribe 会调 wireNameOf，把消息类重新登记进 codec 的注册表、再次钉住
        // 本该被回收的 classloader。
        assertFailsWith<IllegalStateException> { b.subscribe(Hello::class.java) }
    }

    @Test
    fun `destroy 清空线上名注册表以释放 classloader`(client: TestRedisClient) = runBlocking {
        val b = bus(client)
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

/** 与 [Hello] 声明了同一线上名，用于探测注册表状态。 */
@MessageType("com.example.hello.v1")
private data class HelloClash(val who: String = "")

/** 读出 bus 内部的 codec——注册表是否清空只能这样观察。 */
private fun codecOf(bus: RedisMessageBus): MessageCodec {
    val f = RedisMessageBus::class.java.getDeclaredField("codec")
    f.isAccessible = true
    return f.get(bus) as MessageCodec
}
