package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.redis.testing.RedisTestSupport
import com.github.mayblock.easylib.redis.testing.RequiresRedis
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@RequiresRedis
class RedisScopeMetricsTest {

    private val recorded = mutableListOf<String>()

    private val recorder = object : MetricsRecorder {
        override fun <T> record(name: String, block: () -> T): T = block()
        override suspend fun <T> recordSuspending(name: String, block: suspend () -> T): T {
            recorded += name
            return block()
        }
    }

    @Test
    fun `withLock withRetry withMetrics 三层嵌套按顺序生效且返回值一路透传`() = runBlocking {
        val client = RedisTestSupport.newClient(recorder)
        try {
            var attempts = 0
            val result = client.execute {
                withLock("L") {
                    withRetry(3) {
                        withMetrics("cache.get") {
                            attempts++
                            if (attempts < 2) error("boom")
                            "value"
                        }
                    }
                }
            }

            assertEquals("value", result)
            assertEquals(2, attempts)
            // withMetrics 在 withRetry 内层，因此每次尝试都上报一次
            assertEquals(listOf("cache.get", "cache.get"), recorded)
        } finally {
            client.destroy()
        }
    }
}
