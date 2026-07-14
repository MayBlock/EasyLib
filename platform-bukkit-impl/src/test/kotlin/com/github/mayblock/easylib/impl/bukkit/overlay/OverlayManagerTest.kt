package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import io.mockk.mockk
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 不触碰 PacketEvents 的最小 [AbstractPlayerOverlay] 假实现，专供 [OverlayManager] 的跟踪/摘除逻辑测试。 */
private class FakeOverlay(scheduler: TaskScheduler) : AbstractPlayerOverlay(scheduler, emptyMap()) {
    override fun repaint(index: Int) {}
    override fun registerPacketListener(): Disposable = Disposable {}
    override fun show(player: Player) {}
    override fun hide(player: Player): Boolean = false
}

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

    // 说明：同样为绕开 PacketPlayerOverlay/PacketEvents，这里直接用 track() 注入假实现，
    // 单独验证「destroy 触发 onDestroyed -> manager 摘除」这条泄漏链修复逻辑，不经过 create()。
    @Test
    fun `overlay destroy 后 manager 不再持有`() {
        val mgr = OverlayManager(mockk<TaskScheduler>(relaxed = true), MockBukkit.createMockPlugin())
        val overlay = FakeOverlay(mockk<TaskScheduler>(relaxed = true))
        mgr.track(overlay)
        assertEquals(1, mgr.trackedCount)
        overlay.destroy()
        assertEquals(0, mgr.trackedCount)
    }
}
