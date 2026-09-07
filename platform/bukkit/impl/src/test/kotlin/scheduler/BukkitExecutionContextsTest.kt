package com.github.mayblock.easylib.platform.bukkit.impl.scheduler

import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.execute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BukkitExecutionContextsTest {

    private lateinit var server: ServerMock
    private lateinit var contexts: BukkitExecutionContexts

    @BeforeTest
    fun setUp() {
        server = MockBukkit.mock()
        contexts = BukkitExecutionContexts(MockBukkit.createMockPlugin())
    }

    @AfterTest
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `sync executor executes immediately on main thread`() {
        var ran = false

        contexts.sync.execute { ran = Bukkit.isPrimaryThread() }

        assertTrue(ran)
    }

    @Test
    fun `sync executor bridges worker to main thread`() {
        var ran = false
        CompletableFuture.runAsync {
            contexts.sync.execute { ran = Bukkit.isPrimaryThread() }
        }.get(5, TimeUnit.SECONDS)
        assertFalse(ran)

        server.scheduler.performOneTick()

        assertTrue(ran)
    }

    @Test
    fun `async executor runs on worker thread`() {
        val primary = CompletableFuture<Boolean>()

        contexts.async.execute { primary.complete(Bukkit.isPrimaryThread()) }
        server.scheduler.performOneTick()

        assertFalse(primary.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `sync dispatcher finishes cancellation from worker thread`() {
        verifyCancellation(contexts.sync)
    }

    @Test
    fun `async dispatcher finishes cancellation from worker thread`() {
        verifyCancellation(contexts.async)
    }

    private fun verifyCancellation(context: BukkitExecutionContext) {
        val scope = CoroutineScope(SupervisorJob() + context.dispatcher)
        val cleaned = CompletableFuture<Boolean>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                cleaned.complete(Bukkit.isPrimaryThread())
            }
        }
        val completed = CompletableFuture<Unit>()
        job.invokeOnCompletion { completed.complete(Unit) }

        try {
            CompletableFuture.runAsync { job.cancel() }.get(5, TimeUnit.SECONDS)
            server.scheduler.performOneTick()

            completed.get(5, TimeUnit.SECONDS)
            assertTrue(job.isCompleted)
            assertTrue(job.isCancelled)
            if (context is BukkitExecutionContext.Sync) assertTrue(cleaned.get())
            else assertFalse(cleaned.get())
        } finally {
            scope.cancel()
        }
    }
}
