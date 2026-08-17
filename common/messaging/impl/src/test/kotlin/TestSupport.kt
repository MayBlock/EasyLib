package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.messaging.api.MessageType
import com.github.mayblock.easylib.redis.RedisClient
import com.github.mayblock.easylib.redis.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.redisson.client.codec.StringCodec
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/*
 * 本模块所有 RedisMessageBus 测试共用的小工具。测试跑在真实 Redis（@RequiresRedis）上，
 * 用 runBlocking 而不是 runTest——等待的是真实 I/O，虚拟时间无意义。
 */

@MessageType("com.example.hello.v1")
data class Hello(val who: String)

@MessageType("com.example.ping.v1")
data class Ping(val note: String)

data class NotAMessage(val v: Int = 0)

/**
 * 每个用例一个独立的命名空间：频道名因此互不相同，测试之间（以及故意让监听器残留在 Redis 上的
 * 用例）不会互相污染——`FLUSHALL` 清的是数据，清不掉 Pub/Sub 订阅
 */
fun ns(): String = "t" + UUID.randomUUID().toString().replace("-", "").take(8)

/** 直接往频道发一段原始字符串（绕过 codec），用于伪造发送方、类型或畸形 JSON。 */
suspend fun RedisClient.publishRaw(channel: String, body: String) {
    execute { getTopic(channel, StringCodec.INSTANCE).publishAsync(body).await() }
}

/** 在频道上挂一个原始监听器，把收到的每条消息体原样记下来。 */
suspend fun RedisClient.observe(channel: String): List<String> {
    val seen = CopyOnWriteArrayList<String>()
    execute {
        getTopic(channel, StringCodec.INSTANCE)
            .addListenerAsync(String::class.java) { _, body -> seen += body }
            .await()
    }
    return seen
}

/** 组装一条线上信封 JSON。 */
fun envelopeJson(sender: String, type: String, payloadJson: String, id: String = "i1"): String =
    """{"id":"$id","sender":"$sender","type":"$type","time":"2026-08-07T10:00:00Z","payload":$payloadJson}"""

/**
 * 在后台启动对 [bus] 的一次收集（[block] 内应当 collect 由该 bus 派生的 Flow），并等到收集方
 * 真的挂到了 `inbound` 上再返回——`inbound` 是 replay = 0 的 SharedFlow，晚到的订阅方收不到早发的消息。
 */
suspend fun <T> CoroutineScope.collecting(bus: RedisMessageBus, block: suspend () -> T): Deferred<T> {
    val before = bus.inbound.subscriptionCount.value
    val deferred = async(Dispatchers.IO) { block() }
    eventually(message = "collector never subscribed to inbound") { bus.inbound.subscriptionCount.value > before }
    return deferred
}

/** 等待一个必须到达的结果。 */
suspend fun <T> Deferred<T>.awaitSoon(): T = withTimeout(5_000) { await() }

/** 等待一小段时间，确认**没有**东西到达（返回 null 即为「没有」）；超时后取消这次收集，免得挂住 runBlocking。 */
suspend fun <T> Deferred<T>.awaitNothing(): T? =
    withTimeoutOrNull(500) { await() }.also { if (it == null) cancel() }
