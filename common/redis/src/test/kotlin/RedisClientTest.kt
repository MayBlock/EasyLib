package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.redis.testing.RedisTestSupport
import com.github.mayblock.easylib.redis.testing.RequiresRedis
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RequiresRedis
class RedisClientTest {

    @Test
    fun `destroy 幂等，之后 isDestroyed 为 true 且 execute 被拒绝`(): Unit = runBlocking {
        val client = RedisTestSupport.newClient()
        assertFalse(client.isDestroyed)
        client.execute { }

        client.destroy()
        client.destroy()

        assertTrue(client.isDestroyed)
        // destroy 真的关掉了底层 Redisson——execute 的关停校验由真实的 isShutdown 触发。
        assertFailsWith<IllegalStateException> { client.execute { } }
    }
}
