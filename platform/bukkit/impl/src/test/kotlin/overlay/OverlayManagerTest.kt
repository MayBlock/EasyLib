package com.github.mayblock.easylib.platform.bukkit.impl.overlay

import com.github.mayblock.easylib.platform.bukkit.impl.testing.TestSyncContext
import com.github.mayblock.easylib.platform.bukkit.impl.testing.TestAsyncContext
import com.github.mayblock.easylib.base.api.event.on
import com.github.mayblock.easylib.platform.bukkit.api.overlay.OverlayDestroyEvent
import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.listener.OverlayQuitListener
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OverlayManagerTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test
    fun `注册 quit 清理监听器，close 幂等且注销监听器`() {
        val mgr = OverlayManager(
            mockk<TaskScheduler>(relaxed = true),
            TestSyncContext(),
            mockk<PacketManager<Player>>(relaxed = true),
            MockBukkit.createMockPlugin(),
        )
        assertTrue(PlayerQuitEvent.getHandlerList().registeredListeners.any { it.listener is OverlayQuitListener })
        mgr.close()
        mgr.close() // 幂等
        assertTrue(PlayerQuitEvent.getHandlerList().registeredListeners.none { it.listener is OverlayQuitListener })
    }

    @Test
    fun `create 使用注入的 packet manager 注册覆盖层监听器`() {
        val packetManager = mockk<PacketManager<Player>>(relaxed = true)
        val mgr = OverlayManager(
            mockk<TaskScheduler>(relaxed = true),
            TestSyncContext(),
            packetManager,
            MockBukkit.createMockPlugin(),
        )

        mgr.create { }

        verify(exactly = 1) { packetManager.registerListener(any(), any()) }
        mgr.close()
    }

    @Test
    fun `create 默认 Sync 并允许显式 Async 回调上下文`() {
        val tasks = ArrayDeque<() -> Unit>()
        val manager = OverlayManager(mockk(relaxed = true), TestSyncContext(),
            mockk<PacketManager<Player>>(relaxed = true), MockBukkit.createMockPlugin())
        var destroyed = 0
        val sync = manager.create { }
        val async = manager.create(TestAsyncContext(TaskExecutor { tasks.addLast(it) })) { }
        listOf(sync, async).forEach { overlay ->
            overlay.on { on<OverlayDestroyEvent> { destroyed++ } }
        }
        manager.close()
        assertTrue(sync.isDestroyed && async.isDestroyed)
        assertEquals(1, destroyed)
        while (tasks.isNotEmpty()) tasks.removeFirst()()
        assertEquals(2, destroyed)
        assertFailsWith<IllegalStateException> { manager.create { } }
    }
}
