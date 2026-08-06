package com.github.mayblock.easylib.base.impl.metrics

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsRecorderTest {

    @Test
    fun `NoOpMetricsRecorder 的协程版执行块并透传返回值`() = runTest {
        assertEquals("ok", NoOpMetricsRecorder.recordSuspending("op") { "ok" })
    }

    @Test
    fun `不覆写协程版的实现类仍然执行块`() = runTest {
        val recorder = object : MetricsRecorder {
            override fun <T> record(name: String, block: () -> T): T = block()
        }
        var executed = false
        val result = recorder.recordSuspending("op") {
            executed = true
            42
        }
        assertTrue(executed)
        assertEquals(42, result)
    }

    @Test
    fun `覆写协程版的实现类能观测到操作名`() = runTest {
        val seen = mutableListOf<String>()
        val recorder = object : MetricsRecorder {
            override fun <T> record(name: String, block: () -> T): T = block()
            override suspend fun <T> recordSuspending(name: String, block: suspend () -> T): T {
                seen += name
                return block()
            }
        }
        recorder.recordSuspending("cache.get") { Unit }
        assertEquals(listOf("cache.get"), seen)
    }
}
