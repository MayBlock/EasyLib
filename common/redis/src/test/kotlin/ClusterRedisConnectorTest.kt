package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import com.github.mayblock.easylib.redis.connector.ClusterRedisConnector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ClusterRedisConnectorTest {

    private object StubRecorder : MetricsRecorder {
        override fun <T> record(name: String, block: () -> T): T = block()
    }

    @Test
    fun `host port 构造转发 metrics`() {
        val connector = ClusterRedisConnector(host = "127.0.0.1", port = 6379, metrics = StubRecorder)
        assertSame(StubRecorder, connector.metrics)
    }

    @Test
    fun `host port 构造默认使用 NoOpMetricsRecorder`() {
        val connector = ClusterRedisConnector(host = "127.0.0.1", port = 6379)
        assertSame(NoOpMetricsRecorder, connector.metrics)
    }

    @Test
    fun `ssl 决定地址 scheme`() {
        assertEquals(
            listOf("redis://127.0.0.1:6379"),
            ClusterRedisConnector(host = "127.0.0.1", port = 6379).addresses,
        )
        assertEquals(
            listOf("rediss://127.0.0.1:6379"),
            ClusterRedisConnector(host = "127.0.0.1", port = 6379, ssl = true).addresses,
        )
    }
}
