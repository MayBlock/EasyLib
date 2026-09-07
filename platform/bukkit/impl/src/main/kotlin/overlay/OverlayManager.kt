package com.github.mayblock.easylib.platform.bukkit.impl.overlay

import com.github.mayblock.easylib.base.api.event.on
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.execute
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.util.Priority
import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.platform.bukkit.api.overlay.OverlayDestroyEvent
import com.github.mayblock.easylib.platform.bukkit.api.overlay.PlayerOverlay
import com.github.mayblock.easylib.platform.bukkit.api.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.platform.bukkit.api.overlay.dsl.PlayerOverlayScope
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.builder.PlayerOverlayBuilder
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.listener.OverlayQuitListener
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.OverlayView
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.SlotMap
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.transport.PacketOverlayTransport
import com.github.mayblock.easylib.platform.bukkit.impl.util.SlotDisplayMap
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import java.io.Closeable
import java.util.*

/** 覆盖层工厂：创建并跟踪覆盖层，`close()` 时统一销毁（清理更新循环 + 包监听 + 断线监听器）。 */
class OverlayManager(
    private val taskScheduler: TaskScheduler,
    private val sync: BukkitExecutionContext.Sync,
    private val packetManager: PacketManager<Player>,
    private val plugin: Plugin,
) : PlayerOverlayFactory, Closeable {

    // 身份语义（而非 equals），与菜单侧 MenuManager.menus 对齐：注销走的是 `remove(overlay)`，
    // 若将来 PlayerOverlayImpl 获得 equals 覆写，equals 路径会摘错实例。
    private val overlays: MutableSet<PlayerOverlay> = Collections.newSetFromMap(IdentityHashMap())
    private var closed = false

    /** 单一共享的断线清理监听器：把 quit 玩家从所有活动覆盖层移除（防 viewers 泄漏）。 */
    private val quitListener = OverlayQuitListener {
        synchronized(overlays) { overlays.filterIsInstance<PlayerOverlayImpl>() }
    }.also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }

    override fun create(block: PlayerOverlayScope.() -> Unit): PlayerOverlay = create(sync, block)

    override fun create(context: BukkitExecutionContext, block: PlayerOverlayScope.() -> Unit): PlayerOverlay {
        val builder = PlayerOverlayBuilder { slots ->
            val map = SlotMap(slots)
            val display = SlotDisplayMap()
            PlayerOverlayImpl(
                slots,
                map,
                display,
                taskScheduler,
                context,
                PacketOverlayTransport(OverlayView(map, display), packetManager, sync),
            )
        }.apply(block)
        return synchronized(overlays) {
            check(!closed) { "this overlay factory is closed!" }
            builder.build().also { overlay ->
                overlays.add(overlay)
                // MONITOR 垫底，与菜单侧记账一致：上游的 destroy 订阅者先跑完，记账最后做。
                overlay.on {
                    on<OverlayDestroyEvent>(Priority.MONITOR) {
                        synchronized(overlays) { overlays.remove(overlay) }
                    }
                }
            }
        }
    }

    override fun close() {
        val snapshot = synchronized(overlays) {
            if (closed) return
            closed = true
            overlays.toList().also { overlays.clear() }
        }
        snapshot.forEach { it.destroy() }
        sync.execute { HandlerList.unregisterAll(quitListener) }
    }
}
