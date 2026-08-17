package com.github.mayblock.easylib.redis.testing

import com.github.mayblock.easylib.redis.RedisClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 频道 [channel] 在 Redis 上的订阅者数（`PUBSUB NUMSUB`）。
 *
 * 注意 Redisson 按**连接**订阅：同一个 Redisson 客户端上不管挂了几个监听器，Redis 只看到一个
 * 订阅者。因此这个数字适合断言「有没有」（0 / 非 0），不适合断言监听器个数。
 */
suspend fun RedisClient.subscriberCount(channel: String): Long = execute {
    getTopic(channel).countSubscribersAsync().await()
}

/**
 * 轮询直到 [condition] 为真，超过 [timeout] 抛 [AssertionError]。
 *
 * 用于那些"很快但不是同步"的真实副作用（例如注销监听器之后 Redis 侧订阅计数归零）。
 */
suspend fun eventually(
    timeout: Duration = 5.seconds,
    interval: Duration = 20.milliseconds,
    message: String = "condition not met within $timeout",
    condition: suspend () -> Boolean,
) {
    val start = TimeSource.Monotonic.markNow()
    while (!condition()) {
        if (start.elapsedNow() > timeout) throw AssertionError(message)
        delay(interval)
    }
}
