package com.github.mayblock.easylib.platform.bukkit.impl.menu.type.chest

import com.github.mayblock.easylib.base.api.event.EventSource
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.util.Disposable
import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.platform.bukkit.api.menu.MenuCloseEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.MenuDestroyEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.MenuEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.MenuOpenEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.scheduleTask
import com.github.mayblock.easylib.platform.bukkit.impl.menu.BukkitMenu
import com.github.mayblock.easylib.platform.bukkit.impl.menu.MenuEventDispatcher
import com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.SlotSpec
import com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.SlotUpdateLoop
import com.github.mayblock.easylib.platform.bukkit.impl.util.SlotDisplayMap
import com.github.mayblock.easylib.platform.bukkit.impl.util.ViewerRegistry
import com.github.mayblock.easylib.platform.bukkit.impl.util.isEmptyStack
import com.github.mayblock.easylib.platform.bukkit.impl.util.stack
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * 真实容器版箱子菜单的协调者（对标 [com.github.mayblock.easylib.platform.bukkit.impl.overlay.PlayerOverlayImpl] 的组合切分）：
 * 把容器视图（[RealChestView]）、观察者状态（[ViewerRegistry]）、事件面（[MenuEventDispatcher]）
 * 与更新循环（[SlotUpdateLoop]）组合起来，自身只负责编排与点击/拖拽的放行门决策。
 *
 * 自身即 [org.bukkit.inventory.InventoryHolder]；点击/拖拽/开关由
 * [com.github.mayblock.easylib.platform.bukkit.impl.menu.listener.MenuInteractionListener] 按 holder 经 [BukkitMenu] 接口路由回本菜单。
 * 菜单级事件总线仅暴露订阅侧（[EventSource]），`emit` 由 [MenuEventDispatcher] 内部持有。
 */
internal class RealChestMenu(
    private val taskScheduler: TaskScheduler,
    private val syncExecutionContext: BukkitExecutionContext.Sync,
    packetManager: PacketManager<*>,
    override val title: Component,
    override val type: ChestMenuType,
    private val specs: Map<Int, SlotSpec>,
    private val hidePlayerInventory: Boolean = true,
    private val dispatcher: MenuEventDispatcher = MenuEventDispatcher(),
) : ChestMenu, BukkitMenu, EventSource<MenuEvent> by dispatcher {

    private val view = RealChestView(this, type, title, packetManager)
    private val viewers = ViewerRegistry()
    internal val displayMap = SlotDisplayMap() // internal：供同模块测试观察显示缓存
    private val updateLoop = SlotUpdateLoop(
        taskScheduler, syncExecutionContext, specs, this, viewers::snapshot, displayMap
    ) { it.updateInventory() }
    private var hideMask: Disposable? = null
    private var displayMask: Disposable? = null
    private val hasUpdateRules = specs.values.any { it.updateRules.isNotEmpty() }

    private var destroyed = false
    override val isDestroyed: Boolean get() = destroyed

    init {
        // 声明了 onPlace 放行处理器的槽位与背包隐藏互斥：构建期即报错，避免运行时永远无法放置
        require(!(hidePlayerInventory && specs.values.any { it.hasPlaceHandlers })) {
            "onPlace handlers require hidePlayerInventory = false"
        }
        // 初始物品写入真实容器
        specs.forEach { (index, spec) -> if (!spec.item.isEmptyStack()) view.setItem(index, spec.item) }
        // slot 声明的点击处理器挂到菜单总线（按 index 过滤）
        dispatcher.wireSlotHandlers(specs)
        // hide 遮罩只影响 viewers 中的玩家；构造期直接 attach（packetManager 已注入，MockBukkit 下可 mock）
        if (hidePlayerInventory) hideMask = view.attachHideMask(viewers::contains)
        // 显示层改写只在存在 onUpdate 规则时注册（无规则的菜单零开销、行为与旧版一致）
        if (hasUpdateRules) {
            displayMask = view.attachDisplayMask(viewers::contains) { viewerId, slot ->
                displayMap.lookup(viewerId, slot)?.packetItem
            }
        }
    }

    override fun getInventory(): Inventory = view.inventory

    override fun open(player: Player) {
        check(!destroyed) { "this menu is destroyed!" }
        view.open(player) // 触发 InventoryOpenEvent → 监听器 handleOpen
    }

    override fun getItem(index: Int): ItemStack? = view.getItem(index)

    override fun setItem(index: Int, item: ItemStack?) {
        view.setItem(index, item)
        updateLoop.recomputeSlot(index) // 已知新值路径：同步重算显示（spec §8）
    }

    /** 观察者增减是 update loop 的唯一开关（0→1 start、→0 stop），与 overlay 同语义。 */
    override fun handleOpen(player: Player) {
        if (!viewers.add(player)) return
        updateLoop.start() // 幂等
        updateLoop.seed(player) // 同步种子：InventoryOpenEvent 先于首包，首帧即假显示（spec §8）
        dispatcher.publish(MenuOpenEvent(this, player))
    }

    override fun handleClose(player: Player) {
        if (!viewers.remove(player)) return // 幂等：close+quit 双调只派发一次
        displayMap.remove(player.uniqueId)
        if (viewers.isEmpty) updateLoop.stop()
        dispatcher.publish(MenuCloseEvent(this, player))
        // 恢复背包必须推迟到下一 tick：InventoryCloseEvent 派发期间服务端 containerMenu 仍指向
        // 正在关闭的容器窗口，此刻 updateInventory() 重发的是旧 windowId 的内容包；ESC（客户端
        // 主动）关窗时客户端早已本地关窗回到 window 0，旧 windowId 的包会被其直接丢弃，被 hide
        // 遮罩写空的背包区就永远得不到恢复。下一 tick 时 containerMenu 已回到 window 0，重发必达。
        // （服务端主动关闭路径下内容包先于关窗包到达、同步重发碰巧有效，但统一走延迟路径无害。）
        if (hidePlayerInventory) {
            taskScheduler.scheduleTask(TaskScheduler.Trigger.Once, syncExecutionContext) {
                view.refreshBottom(player)
            }
        }
    }

    /**
     * 顺序契约：先置 [destroyed]，再派发 [MenuDestroyEvent]，最后才关总线。
     * - 置位早于派发：订阅者看到的是一致状态（此时 open() 会正确 check 失败）。
     * - 派发早于 close()：close() 会 unsubscribeAll，之后派发无人收听。
     * - closeAll() 早于置位：它会触发 InventoryCloseEvent → handleClose → MenuCloseEvent，
     *   语义上属于「销毁前的正常关窗」，顺序正确。
     */
    override fun destroy() {
        if (destroyed) return
        view.closeAll() // 快照遍历防 CME（见 RealChestView.closeAll）
        updateLoop.stop()
        hideMask?.dispose()
        displayMask?.dispose()
        destroyed = true
        dispatcher.publish(MenuDestroyEvent(this))
        dispatcher.close()
    }

    /** 由监听器在主线程调用：按放行门决策处理一次点击。 */
    override fun handleClick(e: org.bukkit.event.inventory.InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val rawSlot = e.rawSlot
        val isTop = rawSlot in 0 until type.size
        val declared = isTop && specs.containsKey(rawSlot)
        // 信息性 onClick（已声明的顶部槽，任意点击，先于门/转移事件）
        if (declared) {
            dispatcher.publish(InventoryClickEvent(this, player, rawSlot, e.click))
        }
        val decision = ChestSlotGate.decide(isTop, rawSlot, e.action, declared, hidePlayerInventory)
        when (decision) {
            SlotDecision.Deny -> e.isCancelled = true
            SlotDecision.AllowNative -> {}
            is SlotDecision.FireTake -> {
                val ev = SlotTakeEvent(
                    this,
                    decision.slot,
                    player,
                    (e.currentItem ?: stack(Material.AIR)).clone()
                )
                dispatcher.publish(ev)
                if (ev.isCancelled) e.isCancelled = true
                else updateLoop.invalidateSlot(decision.slot)
            }
            is SlotDecision.FirePlace -> {
                val ev = SlotPlaceEvent(
                    this,
                    decision.slot,
                    player,
                    (e.cursor ?: stack(Material.AIR)).clone()
                )
                dispatcher.publish(ev)
                if (ev.isCancelled) e.isCancelled = true
                else updateLoop.invalidateSlot(decision.slot)
            }
            is SlotDecision.FireSwap -> {
                // swap 与 drag 的观察者回调应无副作用，因为 Bukkit 的原子性使某个观察者
                // 可能在稍后整体取消前已触发。此处短路：take 取消时不再派发 place。
                val take = SlotTakeEvent(
                    this,
                    decision.slot,
                    player,
                    (e.currentItem ?: stack(Material.AIR)).clone()
                )
                dispatcher.publish(take)
                if (take.isCancelled) { e.isCancelled = true; return }
                val place = SlotPlaceEvent(
                    this,
                    decision.slot,
                    player,
                    (e.cursor ?: stack(Material.AIR)).clone()
                )
                dispatcher.publish(place)
                if (place.isCancelled) e.isCancelled = true
                else updateLoop.invalidateSlot(decision.slot)
            }
            SlotDecision.ShiftIntoMenu -> {
                e.isCancelled = true
                handleShiftIntoMenu(player, e)
            }
        }
    }

    private fun handleShiftIntoMenu(player: Player, e: org.bukkit.event.inventory.InventoryClickEvent) {
        val source = e.currentItem?.takeUnless { it.isEmptyStack() } ?: return
        // view.getItem 返回拷贝（空槽为 null），planner 只读快照，语义不变。
        // 候选槽预筛：无 DSL onPlace 的槽事件必然保持取消（没人放行），预筛是纯优化；
        // 仅靠外部总线订阅放行的槽不参与 shift 分发（其 hasPlaceHandlers 为 false）。
        val candidates = specs.filterValues { it.hasPlaceHandlers }.keys.sorted().map { it to view.getItem(it) }
        val plan = ShiftIntoMenuPlanner.plan(source, candidates)
        var placedTotal = 0
        val placedSlots = mutableSetOf<Int>()
        for (p in plan) {
            val placing = source.clone().apply { amount = p.amount }
            val ev = SlotPlaceEvent(this, p.slot, player, placing.clone())
            dispatcher.publish(ev)
            if (ev.isCancelled) continue
            // 写入前复核容量：onPlace 订阅者可能在本轮循环中通过 setItem 等方式改动了容器状态，
            // ShiftIntoMenuPlanner 的规划快照可能已经过期，不能盲目信任 p.amount。
            val existing = view.getItem(p.slot) // 拷贝；空槽（含 AIR）为 null
            if (existing == null) {
                view.setItem(p.slot, placing)
                placedTotal += p.amount
                placedSlots += p.slot
            } else {
                val room = existing.maxStackSize - existing.amount
                val add = minOf(room, p.amount)
                if (add <= 0) continue
                existing.amount += add
                view.setItem(p.slot, existing) // getItem 是拷贝，改完须写回
                placedTotal += add
                placedSlots += p.slot
            }
        }
        if (placedTotal > 0) {
            placedSlots.forEach(updateLoop::recomputeSlot) // 已知新值：同步重算，随后的重绘/广播携带新显示
            val remaining = source.amount - placedTotal
            e.currentItem = if (remaining <= 0) null else source.clone().apply { amount = remaining }
            player.updateInventory()
        }
    }

    /** 由监听器在主线程调用：按 spec §4.3 处理一次拖拽（仅向已声明的顶部槽派发 onPlace 放行，否则整体取消）。 */
    override fun handleDrag(e: org.bukkit.event.inventory.InventoryDragEvent) {
        val player = e.whoClicked as? Player ?: return
        val topRaw = e.rawSlots.filter { it in 0 until type.size }
        if (topRaw.isEmpty()) { // 仅在底部（玩家背包）内拖拽
            if (hidePlayerInventory) e.isCancelled = true
            return
        }
        if (topRaw.any { !specs.containsKey(it) }) { e.isCancelled = true; return } // 触及未声明的顶部槽
        for (slot in topRaw) {
            val newItem = e.newItems[slot] ?: continue
            val ev = SlotPlaceEvent(this, slot, player, newItem.clone())
            dispatcher.publish(ev)
            if (ev.isCancelled) { e.isCancelled = true; return } // 原生拖拽只能整体取消
        }
        // 全部放行：拖拽由 Bukkit 在事件返回后应用，走失效路径（spec §8）
        topRaw.forEach { if (e.newItems[it] != null) updateLoop.invalidateSlot(it) }
    }
}
