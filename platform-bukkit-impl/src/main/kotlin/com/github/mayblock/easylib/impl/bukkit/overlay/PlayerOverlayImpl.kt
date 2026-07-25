package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.*
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlaySlotSpec
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotUpdateLoop
import com.github.mayblock.easylib.impl.bukkit.overlay.transport.OverlayTransport
import com.github.mayblock.easylib.impl.bukkit.util.SlotDisplayMap
import com.github.mayblock.easylib.impl.bukkit.util.ViewerRegistry
import com.github.mayblock.easylib.impl.bukkit.util.isEmptyStack
import com.github.mayblock.easylib.impl.bukkit.util.stack
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
    private val display: SlotDisplayMap,
    scheduler: TaskScheduler,
    private val transport: OverlayTransport,
    private val dispatcher: OverlayEventDispatcher = OverlayEventDispatcher(scheduler),
) : PlayerOverlay, EventSource<OverlayEvent> by dispatcher {

    private val viewers = ViewerRegistry()
    private val updateLoop = SlotUpdateLoop(map, scheduler, viewers::snapshot, display, ::repaint)
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
        // 顺序契约：先 start（幂等，建立 fired 记账）→ 再 seed（算出该玩家的显示条目，不发包）
        // → 再 paintAll（此时查显示层已有条目，首帧即正确）→ 最后登记观察者并派发 ShowEvent。
        updateLoop.start()
        updateLoop.seed(player)
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

    /** 观察者增减是 update loop 的唯一开关：start 由 show 负责，→0 时在此 stop（无人观看时不空转）。 */
    private fun addViewer(player: Player) {
        if (!viewers.add(player)) return
        dispatcher.publish(OverlayShowEvent(this, player))
    }

    private fun removeViewer(player: Player): Boolean {
        if (!viewers.remove(player)) return false
        display.remove(player.uniqueId) // 防显示层随玩家泄漏
        if (viewers.isEmpty) updateLoop.stop()
        dispatcher.publish(OverlayHideEvent(this, player))
        return true
    }

    /**
     * 向单个观察者重绘某槽。发包前复查在册与在线：若在调用方取快照之后、发包之前该玩家已被
     * hide/quit 移除，避免向已不再观察的玩家补发鬼影包。
     */
    private fun repaint(player: Player, index: Int) {
        if (player.isOnline && player in viewers) transport.paint(player, index)
    }

    // 拷贝语义：读侧 getItem 出参克隆（外部拿不到活引用）；写侧所有权由 LiveSlot 写时克隆
    // 统一强制（覆盖 setItem、构造、更新循环全部写入路径），故此处无需再 clone 入参。
    override fun getItem(index: Int): ItemStack? =
        map[index]?.item?.takeUnless { it.isEmptyStack() }?.clone()

    /**
     * 基底变更 ⇒ **必须无条件重绘**：菜单侧 setItem 写真实容器、Bukkit 自会发包，overlay 没人代劳。
     * 若只在显示层提交返回 true 时重绘，则「规则不改物品、前后都无显示条目」时提交返回 false，
     * 而基底已变，客户端仍显示旧值。故重算只刷缓存，发包在此统一做。
     */
    override fun setItem(index: Int, item: ItemStack?) {
        val slot = requireNotNull(map[index]) { "slot $index is not declared on this overlay" }
        slot.item = item ?: stack(Material.AIR)
        updateLoop.recomputeSlot(index)
        viewers.snapshot().forEach { repaint(it, index) }
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
