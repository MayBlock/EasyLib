package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.overlay.builder.PlayerOverlayBuilder
import com.github.mayblock.easylib.impl.bukkit.overlay.listener.OverlayQuitListener
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.overlay.transport.PacketOverlayTransport
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

    /** 当前仍被跟踪的覆盖层数量（测试可见：验证 destroy 后 manager 不再持有）。 */
    internal val trackedCount: Int get() = overlays.size

    /** 单一共享的断线清理监听器：把 quit 玩家从所有活动覆盖层移除（防 viewers 泄漏）。 */
    private val quitListener = OverlayQuitListener { overlays.filterIsInstance<PlayerOverlayImpl>() }.also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }

    override fun create(block: PlayerOverlayScope.() -> Unit): PlayerOverlay =
        PlayerOverlayBuilder { slots ->
            val map = SlotMap(slots)
            PlayerOverlayImpl(slots, map, taskScheduler, PacketOverlayTransport(map))
        }
            .apply(block)
            .build()
            .let { it as PlayerOverlayImpl }
            .let(::track)

    /**
     * 登记一个覆盖层实例并挂接销毁回调，销毁时自动从 [overlays] 摘除（防泄漏链）。
     * 抽出为独立函数：既是 [create] 的实现，也便于测试直接注入假实现验证摘除逻辑
     * （真实的 [PlayerOverlayImpl] 依赖 PacketEvents 单例，单测环境下无法构造）。
     */
    internal fun track(overlay: PlayerOverlayImpl): PlayerOverlay {
        overlays.add(overlay)
        overlay.onDestroyed = { overlays.remove(overlay) }
        return overlay
    }

    override fun close() {
        overlays.forEach { it.destroy() }
        HandlerList.unregisterAll(quitListener)
        overlays.clear()
    }
}
