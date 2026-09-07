package com.github.mayblock.easylib.platform.bukkit.impl.scheduler

import com.github.mayblock.easylib.platform.bukkit.api.scheduler.executeCoroutine
import com.github.mayblock.easylib.platform.bukkit.impl.testing.TestAsyncContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BukkitExecutionContextTest {

    @Test
    fun `executeCoroutine returns result after child work completes`(): Unit = runBlocking {
        val order = mutableListOf<String>()
        val result: Any? = TestAsyncContext().executeCoroutine {
            launch {
                yield()
                order += "child"
            }
            order += "body"
            "loaded"
        }

        assertEquals("loaded", result)
        assertEquals(listOf("body", "child"), order)
    }

    @Test
    fun `executeCoroutine propagates block failure`(): Unit = runBlocking {
        val failure = IllegalStateException("load failed")

        val thrown = assertFailsWith<IllegalStateException> {
            TestAsyncContext().executeCoroutine { throw failure }
        }

        assertSame(failure, thrown)
    }

    @Test
    fun `executeCoroutine inherits parent cancellation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var cleaned = false
        val job = scope.launch {
            TestAsyncContext().executeCoroutine {
                try {
                    awaitCancellation()
                } finally {
                    cleaned = true
                }
            }
        }

        scope.cancel()

        assertTrue(cleaned)
        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
    }
}
