package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.SlotGrid
import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 引擎的渲染出口：全部包发送/背包重同步经由本接口，由 [VirtualChestMenu] 实现，测试用录制型替身。
 */
internal interface ChestClickRenderer {
    /** 把某菜单槽位的当前物品重绘给所有观看者。命名避开 AbstractVirtualMenu.repaint（protected），防止覆写可见性冲突。 */
    fun repaintSlot(index: Int)

    /** 重发某玩家的虚拟光标权威状态（null=清空）。 */
    fun sendCursor(player: Player, item: ItemStack?)

    /** 把窗口内某槽位刷为空气（用于「视觉拿起」真实物品后该槽显示为空）。 */
    fun sendWindowSlotEmpty(player: Player, windowSlot: Int)

    /** 拒绝路径的权威重刷：菜单区按 grid、背包区按 hide 配置（空气或 updateInventory）。 */
    fun resyncSlots(player: Player, slots: Collection<Int>)

    /** 边界转移提交/取消后的背包区重同步：hide → 点击槽刷空气维持屏蔽；否则 updateInventory。 */
    fun resyncBottomAfterTransfer(player: Player, clickedWindowSlot: Int)

    /** 重发真实背包（仅在背包可见的路径调用）。 */
    fun updatePlayerInventory(player: Player)
}

/**
 * 点击副作用壳：Netty 线程受理点击快照，经 [TaskScheduler]（Once + 同步）串行落到主线程，
 * 用纯核心 [ChestClickLogic] 决策后执行 grid 变更、事件派发与渲染。
 *
 * 主线程串行执行天然避免共享 grid 的并发复合写（异步更新循环仍可能交错覆写 `LiveSlot.item`，
 * 与既有点击-更新竞态一致：last-write-wins，各效果方法做保守的过期快照重检）。
 */
internal class ChestClickEngine(
    private val menu: Menu,
    private val grid: SlotGrid,
    private val menuSize: Int,
    private val hidePlayerInventory: Boolean,
    hasPlaceableSlot: Boolean,
    private val scheduler: TaskScheduler,
    private val publish: (MenuEvent) -> Unit,
    private val renderer: ChestClickRenderer,
    private val isViewing: (Player) -> Boolean,
) {

    /** CLICK_WINDOW 包在 Netty 线程的不可变快照。 */
    class ClickSnapshot(
        val windowSlot: Int,
        val button: Int,
        val clickType: WindowClickType,
        val involvedSlots: List<Int>,
        val bukkitClickType: ClickType,
    )

    private val logic = ChestClickLogic(menuSize, hidePlayerInventory, hasPlaceableSlot)
    private val cursors = ConcurrentHashMap<UUID, VirtualCursor>()

    fun cursorOf(player: Player): VirtualCursor? = cursors[player.uniqueId]

    /** Netty 线程调用：调度到主线程处理（包本身已在监听器中被取消）。 */
    fun submit(player: Player, snapshot: ClickSnapshot) {
        scheduler.scheduleTask { onTick = { process(player, snapshot) } }
    }

    /** 观察者移除（关窗/断线/销毁/窗口被顶替）时调用（任意线程）：主线程清理光标。 */
    fun onViewerRemoved(player: Player) {
        scheduler.scheduleTask { onTick = { cleanupCursor(player) } }
    }

    private fun cleanupCursor(player: Player) {
        val cursor = cursors.remove(player.uniqueId) ?: return
        val origin = cursor.origin as? CursorOrigin.MenuSlot ?: return // 背包源：真实物品从未离开背包，无需处理
        val slot = grid[origin.index] ?: return
        val current = slot.item
        when {
            current.isEmptyStack() -> {
                slot.item = cursor.item
                renderer.repaintSlot(origin.index)
            }
            current.isSimilar(cursor.item) && current.amount + cursor.item.amount <= current.maxStackSize -> {
                slot.item = current.clone().apply { amount += cursor.item.amount }
                renderer.repaintSlot(origin.index)
            }
            else -> logger.debug(
                "discarding virtual cursor item {} x{} of {}: origin slot {} occupied",
                cursor.item.type, cursor.item.amount, player.name, origin.index,
            )
        }
    }

    private fun process(player: Player, snapshot: ClickSnapshot) {
        if (!player.isOnline || !isViewing(player)) return
        // 兼容：对所有点击照常发布信息性 InventoryClickEvent（先于可取消的边界事件）
        snapshot.involvedSlots.forEach { slot ->
            publish(InventoryClickEvent(menu, player, slot, snapshot.bukkitClickType))
        }
        if (snapshot.clickType != WindowClickType.PICKUP) return deny(player, snapshot)
        if (snapshot.button != 0 && snapshot.button != 1) return deny(player, snapshot)
        // -999（窗口外丢弃）及越界一律安全回退
        if (snapshot.windowSlot < 0 || snapshot.windowSlot >= menuSize + 36) return deny(player, snapshot)

        val windowSlot = snapshot.windowSlot
        val right = snapshot.button == 1
        val cursor = cursors[player.uniqueId]
        val menuView = if (windowSlot < menuSize) {
            grid[windowSlot]?.let { SlotView(it.item, it.movable, it.placeable) }
        } else null
        val bottomItem = if (windowSlot >= menuSize) {
            player.inventory.getItem(chestWindowSlotToBukkit(windowSlot, menuSize))
        } else null

        when (val decision = logic.decide(windowSlot, right, cursor, menuView, bottomItem)) {
            is ClickDecision.Deny -> deny(player, snapshot)
            is ClickDecision.PickupFromMenu -> pickupFromMenu(player, snapshot, decision)
            is ClickDecision.PickupFromInventory -> pickupFromInventory(player, decision, bottomItem!!)
            is ClickDecision.PlaceInMenu -> placeInMenu(player, snapshot, decision, cursor!!)
            is ClickDecision.SwapWithMenu -> swapWithMenu(player, snapshot, decision, cursor!!)
            is ClickDecision.PutBackToInventory -> putBack(player)
            is ClickDecision.DropToInventory -> dropToInventory(player, decision, cursor!!)
        }
    }

    /** 拒绝：重发光标真值 + 涉及槽位的权威状态。 */
    private fun deny(player: Player, snapshot: ClickSnapshot) {
        renderer.sendCursor(player, cursors[player.uniqueId]?.item)
        renderer.resyncSlots(player, (snapshot.involvedSlots + snapshot.windowSlot).filter { it >= 0 })
    }

    private fun pickupFromMenu(player: Player, snapshot: ClickSnapshot, d: ClickDecision.PickupFromMenu) {
        val slot = grid[d.slot] ?: return deny(player, snapshot)
        val current = slot.item
        // 决策与提交间可能被异步更新循环覆写：过期则安全回退
        if (current.isEmptyStack() || d.amount > current.amount) return deny(player, snapshot)
        val taken = current.clone().apply { amount = d.amount }
        val remainderAmount = current.amount - d.amount
        slot.item = if (remainderAmount <= 0) ItemStack(Material.AIR)
        else current.clone().apply { amount = remainderAmount }
        cursors[player.uniqueId] = VirtualCursor(taken, CursorOrigin.MenuSlot(d.slot))
        renderer.sendCursor(player, taken)
        renderer.repaintSlot(d.slot)
    }

    private fun pickupFromInventory(player: Player, d: ClickDecision.PickupFromInventory, real: ItemStack) {
        val snapshot = real.clone()
        cursors[player.uniqueId] = VirtualCursor(snapshot, CursorOrigin.PlayerInventory(d.windowSlot))
        renderer.sendCursor(player, snapshot)
        renderer.sendWindowSlotEmpty(player, d.windowSlot) // 物品「在光标上」，槽位视觉置空（真实背包未动）
    }

    private fun placeInMenu(player: Player, snapshot: ClickSnapshot, d: ClickDecision.PlaceInMenu, cursor: VirtualCursor) {
        val slot = grid[d.slot] ?: return deny(player, snapshot)
        val placed = cursor.item.clone().apply { amount = d.amount }
        if (d.fromInventory) {
            val sourceWindowSlot = (cursor.origin as CursorOrigin.PlayerInventory).windowSlot
            val event = SlotPlaceEvent(
                menu, d.slot, player, placed.clone(),
                sourceSlot = chestWindowSlotToBukkit(sourceWindowSlot, menuSize),
            )
            publish(event)
            if (event.isCancelled) {
                renderer.sendCursor(player, cursor.item)
                renderer.repaintSlot(d.slot)
                renderer.resyncBottomAfterTransfer(player, sourceWindowSlot)
                return
            }
        }
        val current = slot.item
        slot.item = if (current.isEmptyStack()) placed else current.clone().apply { amount += placed.amount }
        val remaining = cursor.item.amount - placed.amount
        if (remaining <= 0) cursors.remove(player.uniqueId)
        else cursors[player.uniqueId] = VirtualCursor(cursor.item.clone().apply { amount = remaining }, cursor.origin)
        renderer.sendCursor(player, cursors[player.uniqueId]?.item)
        renderer.repaintSlot(d.slot)
        if (d.fromInventory) {
            // 渲染 place 回调对真实背包的扣除（placeable 菜单必然 hide=false）
            renderer.resyncBottomAfterTransfer(player, (cursor.origin as CursorOrigin.PlayerInventory).windowSlot)
        }
    }

    private fun swapWithMenu(player: Player, snapshot: ClickSnapshot, d: ClickDecision.SwapWithMenu, cursor: VirtualCursor) {
        val slot = grid[d.slot] ?: return deny(player, snapshot)
        val slotItem = slot.item
        if (slotItem.isEmptyStack()) return deny(player, snapshot) // 过期快照回退
        slot.item = cursor.item
        cursors[player.uniqueId] = VirtualCursor(slotItem, CursorOrigin.MenuSlot(d.slot))
        renderer.sendCursor(player, slotItem)
        renderer.repaintSlot(d.slot)
    }

    private fun putBack(player: Player) {
        cursors.remove(player.uniqueId)
        renderer.sendCursor(player, null)
        renderer.updatePlayerInventory(player) // 背包源光标仅存在于 hide=false 场景
    }

    private fun dropToInventory(player: Player, d: ClickDecision.DropToInventory, cursor: VirtualCursor) {
        val originIndex = (cursor.origin as CursorOrigin.MenuSlot).index
        val event = SlotTakeEvent(
            menu, originIndex, player, cursor.item.clone(),
            targetSlot = chestWindowSlotToBukkit(d.windowSlot, menuSize),
        )
        publish(event)
        if (event.isCancelled) {
            renderer.sendCursor(player, cursor.item)              // 客户端已预测放下 → 恢复光标
            renderer.resyncBottomAfterTransfer(player, d.windowSlot)
            return
        }
        cursors.remove(player.uniqueId)
        renderer.sendCursor(player, null)
        renderer.resyncBottomAfterTransfer(player, d.windowSlot)  // 渲染 take 回调的真实给予
    }

    companion object {
        private val logger by lazy { LoggerFactory.getLogger(ChestClickEngine::class.java) }
    }
}
