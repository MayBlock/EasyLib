package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import java.io.Closeable

/** 覆盖层工厂：创建并跟踪覆盖层，`close()` 时统一销毁（清理更新循环 + 包监听）。 */
class OverlayManager(
    private val taskScheduler: TaskScheduler,
) : PlayerOverlayFactory, Closeable {

    private val overlays = mutableListOf<PlayerOverlay>()

    override fun create(builder: PlayerOverlayScope.() -> Unit): PlayerOverlay =
        PlayerOverlayBuilder { slots -> PacketPlayerOverlay(taskScheduler, slots) }
            .apply(builder)
            .build()
            .also(overlays::add)

    override fun close() {
        overlays.forEach { it.destroy() }
        overlays.clear()
    }
}
