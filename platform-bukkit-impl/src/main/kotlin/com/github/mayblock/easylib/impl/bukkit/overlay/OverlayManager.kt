package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlayDestroyEvent
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.overlay.builder.PlayerOverlayBuilder
import com.github.mayblock.easylib.impl.bukkit.overlay.listener.OverlayQuitListener
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.overlay.transport.PacketOverlayTransport
import com.github.mayblock.easylib.impl.bukkit.scheduler.BukkitAsyncExecutor
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import java.io.Closeable

/** 覆盖层工厂：创建并跟踪覆盖层，`close()` 时统一销毁（清理更新循环 + 包监听 + 断线监听器）。 */
class OverlayManager(
    private val taskScheduler: TaskScheduler,
    private val plugin: Plugin,
) : PlayerOverlayFactory, Closeable {

    private val overlays = mutableListOf<PlayerOverlay>()

    /** 单一共享的断线清理监听器：把 quit 玩家从所有活动覆盖层移除（防 viewers 泄漏）。 */
    private val quitListener = OverlayQuitListener { overlays.filterIsInstance<PlayerOverlayImpl>() }.also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }

    override fun create(block: PlayerOverlayScope.() -> Unit): PlayerOverlay =
        PlayerOverlayBuilder { slots ->
            val map = SlotMap(slots)
            PlayerOverlayImpl(slots, map, taskScheduler, BukkitAsyncExecutor(plugin), PacketOverlayTransport(map))
        }
            .apply(block)
            .build()
            .let { it as PlayerOverlayImpl }
            .also { overlay ->
                overlays.add(overlay)
                // MONITOR 垫底，与菜单侧记账一致：上游的 destroy 订阅者先跑完，记账最后做。
                overlay.on { on<OverlayDestroyEvent>(Priority.MONITOR) { overlays.remove(overlay) } }
            }

    override fun close() {
        overlays.forEach { it.destroy() }
        HandlerList.unregisterAll(quitListener)
        overlays.clear()
    }
}
