package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlayDestroyEvent
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.overlay.transport.OverlayTransport
import io.mockk.mockk
import org.bukkit.entity.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 不触碰 PacketEvents 的最小 [OverlayTransport] 假实现（真实 PacketOverlayTransport 依赖 PacketEvents 单例）。 */
private class NoopTransport : OverlayTransport {
    override fun paintAll(player: Player) {}
    override fun paint(player: Player, slot: Int) {}
    override fun restore(player: Player) {}
    override fun attach(callbacks: OverlayTransport.Callbacks): Disposable = Disposable {}
}

private fun overlay(): PlayerOverlayImpl {
    val map = SlotMap(emptyMap())
    return PlayerOverlayImpl(
        emptyMap(),
        map,
        mockk<TaskScheduler>(relaxed = true),
        mockk<TaskExecutor>(relaxed = true),
        NoopTransport(),
    )
}

class PlayerOverlayImplDestroyTest {

    @Test
    fun `destroy 派发 OverlayDestroyEvent 给订阅者`() {
        val o = overlay()
        var destroys = 0
        o.on { on<OverlayDestroyEvent> { destroys++ } }

        o.destroy()
        o.destroy() // 幂等：isDestroyed 短路，不应重复派发

        assertEquals(1, destroys, "OverlayDestroyEvent 应恰好派发一次")
    }

    @Test
    fun `派发 OverlayDestroyEvent 时 isDestroyed 已置位（订阅者看到一致状态）`() {
        val o = overlay()
        var seenDestroyed: Boolean? = null
        o.on { on<OverlayDestroyEvent> { seenDestroyed = overlay.isDestroyed } }

        o.destroy()

        assertTrue(seenDestroyed == true, "派发须晚于 isDestroyed = true；否则订阅者读到不一致状态")
    }
}
