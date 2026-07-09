package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import java.io.Closeable

/** 覆盖层工厂：创建并跟踪覆盖层，`close()` 时统一销毁（清理更新循环 + 包监听 + 断线监听器）。 */
class OverlayManager(
    private val taskScheduler: TaskScheduler,
    plugin: Plugin,
) : PlayerOverlayFactory, Closeable {

    private val overlays = mutableListOf<PlayerOverlay>()

    /** 单一共享的断线清理监听器：把 quit 玩家从所有活动覆盖层移除（防 viewers 泄漏）。 */
    private val quitListener = OverlayQuitListener { overlays.filterIsInstance<AbstractPlayerOverlay>() }.also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }

    override fun create(builder: PlayerOverlayScope.() -> Unit): PlayerOverlay =
        PlayerOverlayBuilder { slots -> PacketPlayerOverlay(taskScheduler, slots) }
            .apply(builder)
            .build()
            .also(overlays::add)

    override fun close() {
        overlays.forEach { it.destroy() }
        HandlerList.unregisterAll(quitListener)
        overlays.clear()
    }
}
