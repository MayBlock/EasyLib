package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.redis.connector.SingleRedisConnector
import com.github.mayblock.easylib.redis.testing.RedisTestSupport
import com.github.mayblock.easylib.redis.testing.RequiresRedis
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingleRedisConnectorTest {

    @Test
    fun `host port 构造拼出地址，ssl 决定 scheme`() {
        assertEquals("redis://127.0.0.1:6379", SingleRedisConnector(host = "127.0.0.1").address)
        assertEquals("rediss://127.0.0.1:6380", SingleRedisConnector(host = "127.0.0.1", port = 6380, ssl = true).address)
        assertEquals(listOf("redis://127.0.0.1:6379"), SingleRedisConnector("redis://127.0.0.1:6379").addresses)
    }

}

@RequiresRedis
class SingleRedisConnectorIntegrationTest {

    @Test
    fun `能连上真实单机 Redis 并读写`() = runBlocking {
        val client = SingleRedisConnector(RedisTestSupport.address)
        try {
            val value = client.execute {
                getBucket<String>("single-connector:test").setAsync("ok").await()
                getBucket<String>("single-connector:test").getAsync().await()
            }
            assertEquals("ok", value)
        } finally {
            client.destroy()
        }
        assertTrue(client.isDestroyed)
    }
}
