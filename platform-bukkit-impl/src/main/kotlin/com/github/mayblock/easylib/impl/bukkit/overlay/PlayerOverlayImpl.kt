package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.*
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlaySlotSpec
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotUpdateLoop
import com.github.mayblock.easylib.impl.bukkit.overlay.transport.OverlayTransport
import com.github.mayblock.easylib.impl.bukkit.util.ViewerRegistry
import com.github.mayblock.easylib.impl.bukkit.util.isEmptyStack
import com.github.mayblock.easylib.impl.bukkit.util.item
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

/**
 * 覆盖层协调者（组合切分，替代原「抽象基类 + 包实现子类」的继承切分）：
 * 把观察者状态（[ViewerRegistry]）、客户端通道策略（[com.github.mayblock.easylib.impl.bukkit.overlay.transport.OverlayTransport]）、事件面
 * （[OverlayEventDispatcher]）与更新循环（[SlotUpdateLoop]）组合起来，自身只负责编排。
 *
 * 「移除 viewer + 还原视觉」这一组合此前在 hide/hideIfViewing/destroy 三处各写一遍，
 * 现收敛为 `removeViewer` + `transport.restore` 单一路径。
 */
internal class PlayerOverlayImpl(
    specs: Map<Int, OverlaySlotSpec>,
    private val map: SlotMap,
    scheduler: TaskScheduler,
    executor: TaskExecutor,
    private val transport: OverlayTransport,
    private val dispatcher: OverlayEventDispatcher = OverlayEventDispatcher(scheduler),
) : PlayerOverlay, EventSource<OverlayEvent> by dispatcher {

    private val viewers = ViewerRegistry()
    private val updateLoop = SlotUpdateLoop(map, scheduler, executor, ::repaint)
    private var transportSub: Disposable? = null

    override var isDestroyed: Boolean = false
        private set

    init {
        dispatcher.wireSlotHandlers(specs)
        transportSub = transport.attach(TransportCallbacks())
        // 注意：不在这里启动 updateLoop —— 按需启停（见 addViewer/removeViewer），
        // 无观察者时不空转 update 规则。
    }

    override fun show(player: Player) {
        check(!isDestroyed) { "this overlay is destroyed!" }
        transport.paintAll(player)
        addViewer(player)
    }

    override fun hide(player: Player): Boolean {
        check(!isDestroyed) { "this overlay is destroyed!" }
        return removeViewer(player).ifTrue { transport.restore(player) }
    }

    /**
     * 玩家断线时的清理（由 [com.github.mayblock.easylib.impl.bukkit.overlay.listener.OverlayQuitListener] 调用）：移除观察者并派发 [OverlayHideEvent]。
     * 不调用 `transport.restore`——客户端已断开，无需也无法还原其视觉。
     */
    internal fun onPlayerQuit(player: Player) {
        removeViewer(player)
    }

    /**
     * 玩家打开任意其他容器界面时的兜底清理（由 [com.github.mayblock.easylib.impl.bukkit.overlay.listener.OverlayQuitListener] 监听 `InventoryOpenEvent` 调用）：
     * 若玩家仍在观察，移除观察者并还原视觉，防止容器界面绕过覆盖层看到真实背包。
     */
    internal fun hideIfViewing(player: Player) {
        if (removeViewer(player)) transport.restore(player)
    }

    /** 观察者增减是 update loop 的唯一开关：0→1 启动、→0 停止（无人观看时不空转 update 规则）。 */
    private fun addViewer(player: Player) {
        if (!viewers.add(player)) return
        updateLoop.start() // 幂等
        dispatcher.publish(OverlayShowEvent(this, player))
    }

    private fun removeViewer(player: Player): Boolean {
        if (!viewers.remove(player)) return false
        if (viewers.isEmpty) updateLoop.stop()
        dispatcher.publish(OverlayHideEvent(this, player))
        return true
    }

    private fun repaint(index: Int) = viewers.snapshot().forEach { player ->
        // 遍历前先快照一份 viewers；循环体内再复查一次——
        // 若在快照之后、发包之前该玩家已被 hide/quit 移除，避免向已不再观察的玩家补发鬼影包。
        if (player.isOnline && player in viewers) transport.paint(player, index)
    }

    // 拷贝语义：读侧 getItem 出参克隆（外部拿不到活引用）；写侧所有权由 LiveSlot 写时克隆
    // 统一强制（覆盖 setItem、构造、更新循环全部写入路径），故此处无需再 clone 入参。
    override fun getItem(index: Int): ItemStack? =
        map[index]?.item?.takeUnless { it.isEmptyStack() }?.clone()

    override fun setItem(index: Int, item: ItemStack?) {
        val slot = requireNotNull(map[index]) { "slot $index is not declared on this overlay" }
        slot.item = item ?: item(Material.AIR)
        repaint(index)
    }

    /**
     * 顺序契约（与菜单侧 [com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu.destroy] 一致）：
     * 先置 [isDestroyed]，再派发 [OverlayDestroyEvent]，最后才关总线。
     * - 置位早于派发：订阅者看到的是一致状态（此时 show/hide 会正确 check 失败）。
     * - 派发早于 close()：close() 会 unsubscribeAll，之后派发无人收听。
     * - removeViewer 循环早于置位：它派发 [OverlayHideEvent]，语义上属于「销毁前的正常关闭」。
     */
    override fun destroy() {
        if (isDestroyed) return
        viewers.snapshot().forEach { player ->
            removeViewer(player)
            transport.restore(player)
        }
        updateLoop.stop()
        transportSub?.dispose()
        viewers.clear()
        isDestroyed = true
        dispatcher.publish(OverlayDestroyEvent(this))
        dispatcher.close()
    }

    private inner class TransportCallbacks : OverlayTransport.Callbacks {
        override fun isViewer(player: Player): Boolean = player in viewers

        override fun onClick(player: Player, slot: Int, clickType: ClickType) {
            dispatcher.publishOnMainThread(
                OverlaySlotActionEvent.Click(this@PlayerOverlayImpl, slot, player, clickType)
            )
        }

        override fun onInteract(player: Player, slot: Int, action: OverlaySlotActionEvent.Interact.Action) {
            dispatcher.publishOnMainThread(
                OverlaySlotActionEvent.Interact(this@PlayerOverlayImpl, slot, player, action)
            )
        }
    }
}
