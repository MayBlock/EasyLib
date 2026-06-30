package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuOpenEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.event.EventListener
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import org.bukkit.entity.Player

/**
 * 两类虚拟菜单的共用机制：菜单级事件总线（仅暴露订阅侧）、观察者集合、槽网格、更新循环、
 * 包监听生命周期、点击派发与销毁模板。具体菜单只实现各自差异（windowId、open/activate、包映射、重绘）。
 */
internal abstract class AbstractVirtualMenu(
    scheduler: TaskScheduler,
    specs: Map<Int, SlotSpec>,
    private val bus: SimpleEventBus<MenuEvent> = SimpleEventBus(),
) : VirtualMenu, EventSource<MenuEvent> by bus {

    protected val grid = SlotGrid(specs)
    private val viewers = mutableSetOf<Player>()
    val activeViewers: Set<Player> get() = viewers

    private val updateLoop = MenuUpdateLoop(this, grid, scheduler, ::repaint)
    private var packetListener: Disposable? = null

    final override var isDestroyed: Boolean = false
        private set

    init {
        // 把每个槽声明的点击处理器，作为「按 index 过滤」的监听挂到菜单总线上。
        specs.forEach { (index, spec) ->
            spec.clickHandlers.forEach { handler ->
                bus.subscribe(
                    EventListener<SlotClickEvent>(
                        handler.type,
                        null,
                        { if (index == this.index) handler.block(this) },
                        handler.priority,
                    )
                )
            }
        }
    }

    /** 子类在自身字段就绪后（构造末尾）调用：启动更新循环并注册包监听。 */
    protected fun startMenu() {
        updateLoop.start()
        packetListener = registerPacketListener()
    }

    protected fun publish(event: MenuEvent) = bus.emit(event)

    protected fun addViewer(player: Player) {
        if (viewers.add(player)) publish(MenuOpenEvent(this, player))
    }

    protected fun removeViewer(player: Player): Boolean =
        viewers.remove(player).also { if (it) publish(MenuCloseEvent(this, player)) }

    /** 把某个槽的当前物品重绘给所有在线观察者（类型相关）。 */
    protected abstract fun repaint(index: Int)

    /** 注册本菜单的包监听器（类型相关：包 → 点击事件映射）。 */
    protected abstract fun registerPacketListener(): Disposable

    /** 观察者离开 / 菜单销毁时的清理（类型相关，如还原背包）。 */
    protected open fun onClose(player: Player) {}

    final override fun destroy() {
        if (isDestroyed) return
        activeViewers.toList().forEach { player ->
            removeViewer(player)
            onClose(player)
        }
        updateLoop.stop()
        packetListener?.dispose()
        bus.unsubscribeAll()
        viewers.clear()
        isDestroyed = true
    }
}
