package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import io.mockk.mockk
import org.bukkit.event.player.PlayerQuitEvent
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class OverlayManagerTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    // 说明：create{} 会构造 PacketPlayerOverlay → 触发 PacketEvents/单例，单测环境不可跑，
    // 故此处仅冒烟覆盖生命周期与断线监听器的注册/注销；覆盖层创建与渲染的正确性由手动验证兜底（spec §6）。
    @Test
    fun `注册 quit 清理监听器，close 幂等且注销监听器`() {
        val mgr = OverlayManager(mockk<TaskScheduler>(relaxed = true), MockBukkit.createMockPlugin())
        assertTrue(PlayerQuitEvent.getHandlerList().registeredListeners.any { it.listener is OverlayQuitListener })
        mgr.close()
        mgr.close() // 幂等
        assertTrue(PlayerQuitEvent.getHandlerList().registeredListeners.none { it.listener is OverlayQuitListener })
    }
}
