package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayHideEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayShowEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.event.EventBus
import com.github.mayblock.easylib.api.event.EventListener
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.util.isEmptyStack
import com.github.mayblock.easylib.impl.bukkit.util.item
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap

/**
 * 覆盖层共用机制：覆盖层级事件总线（仅暴露订阅侧）、观察者集合、槽网格、更新循环、
 * 包监听生命周期、槽事件派发与销毁模板。具体覆盖层只实现各自差异（show/hide、包映射、重绘）。
 */
internal abstract class AbstractPlayerOverlay(
    protected val scheduler: TaskScheduler,
    specs: Map<Int, OverlaySlotSpec>,
    private val bus: EventBus<OverlayEvent> = SimpleEventBus(),
) : PlayerOverlay, EventSource<OverlayEvent> by bus {

    protected val grid = SlotGrid(specs)

    // netty（包收发）与主线程都会读写观察者集合，用并发集合防数据竞争（详见 repaint 里的补充过滤）。
    private val viewers: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    val activeViewers: Set<Player> get() = viewers

    private val updateLoop = OverlayUpdateLoop(grid, scheduler, ::repaint)
    private var packetListener: Disposable? = null

    /** 覆盖层销毁时的清理钩子（由持有者，如 [OverlayManager]，挂接以停止追踪本实例）。 */
    internal var onDestroyed: (() -> Unit)? = null

    final override var isDestroyed: Boolean = false
        private set

    init {
        // 把每个槽声明的点击/交互处理器，作为「按 index 过滤」的监听挂到覆盖层总线上。
        specs.forEach { (index, spec) ->
            spec.handlers.forEach { handler ->
                bus.subscribe(
                    EventListener(
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
    protected fun startOverlay() {
        updateLoop.start()
        packetListener = registerPacketListener()
    }

    // 拷贝语义：读侧 getItem 出参克隆（外部拿不到活引用）；写侧所有权由 LiveSlot 写时克隆
    // 统一强制（覆盖 setItem、构造、更新循环全部写入路径），故此处无需再 clone 入参。
    final override fun getItem(index: Int): ItemStack? =
        grid[index]?.item?.takeUnless { it.isEmptyStack() }?.clone()

    final override fun setItem(index: Int, item: ItemStack?) {
        val slot = requireNotNull(grid[index]) { "slot $index is not declared on this overlay" }
        slot.item = item ?: item(Material.AIR)
        repaint(index)
    }

    protected fun publish(event: OverlayEvent) = bus.emit(event)

    protected fun addViewer(player: Player) {
        if (viewers.add(player)) publish(OverlayShowEvent(this, player))
    }

    protected fun removeViewer(player: Player): Boolean =
        viewers.remove(player).also { if (it) publish(OverlayHideEvent(this, player)) }

    /**
     * 玩家断线时的清理（由 [OverlayQuitListener] 调用）：移除观察者并派发 [OverlayHideEvent]。
     * 不调用 [onHide]——客户端已断开，无需也无法还原其视觉。
     */
    internal fun onPlayerQuit(player: Player) {
        removeViewer(player)
    }

    /**
     * 玩家打开任意其他容器界面时的兜底清理（由 [OverlayQuitListener] 监听 `InventoryOpenEvent` 调用）：
     * 若玩家仍在观察，移除观察者并调用 [onHide] 还原视觉，防止容器界面绕过覆盖层看到真实背包。
     */
    internal fun hideIfViewing(player: Player) {
        if (player in activeViewers) removeViewer(player).also { if (it) onHide(player) }
    }

    /** 把某槽当前物品重绘给所有在线观察者（类型相关）。 */
    protected abstract fun repaint(index: Int)

    /** 注册本覆盖层的包监听器（类型相关：包 → 事件映射）。 */
    protected abstract fun registerPacketListener(): Disposable

    /**
     * 观察者离开（hide）与覆盖层销毁时的清理钩子（类型相关，如还原背包）。
     * 销毁路径由本基类调用；hide 路径由子类的 hide 实现在成功移除观察者后调用。
     */
    protected open fun onHide(player: Player) {}

    final override fun destroy() {
        if (isDestroyed) return
        activeViewers.toList().forEach { player ->
            removeViewer(player)
            onHide(player)
        }
        updateLoop.stop()
        packetListener?.dispose()
        bus.unsubscribeAll()
        viewers.clear()
        isDestroyed = true
        onDestroyed?.invoke()
    }
}
