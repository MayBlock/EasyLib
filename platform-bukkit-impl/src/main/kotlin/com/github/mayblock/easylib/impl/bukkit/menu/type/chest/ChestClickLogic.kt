package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import org.bukkit.inventory.ItemStack

/** 虚拟光标物品的来源。 */
internal sealed interface CursorOrigin {
    /** 从菜单槽位拿起。 */
    class MenuSlot(val index: Int) : CursorOrigin

    /** 从玩家真实背包拿起（[windowSlot] 为箱子窗口坐标；真实物品从未离开背包）。 */
    class PlayerInventory(val windowSlot: Int) : CursorOrigin
}

/** per-player 虚拟光标：物品副本 + 来源。 */
internal class VirtualCursor(val item: ItemStack, val origin: CursorOrigin)

/** 决策所需的菜单槽位视图（与运行态解耦，便于纯单测）。 */
internal class SlotView(val item: ItemStack?, val movable: Boolean, val placeable: Boolean)

/** PICKUP 点击的决策结果；副作用由 ChestClickEngine 执行。 */
internal sealed interface ClickDecision {
    object Deny : ClickDecision

    /** 从菜单槽位拿起 [amount] 个到虚拟光标（纯虚拟）。 */
    class PickupFromMenu(val slot: Int, val amount: Int) : ClickDecision

    /** 从玩家真实背包槽位「视觉拿起」（不动真实背包，仅记录来源）。 */
    class PickupFromInventory(val windowSlot: Int) : ClickDecision

    /** 放入菜单槽位；[fromInventory] 时须先派发 SlotPlaceEvent。 */
    class PlaceInMenu(val slot: Int, val amount: Int, val fromInventory: Boolean) : ClickDecision

    /** 菜单源光标与槽位异类物品交换（纯虚拟）。 */
    class SwapWithMenu(val slot: Int) : ClickDecision

    /** 背包源光标放回原真实槽位（视觉还原）。 */
    object PutBackToInventory : ClickDecision

    /** 菜单源光标落入背包区 → 派发 SlotTakeEvent。 */
    class DropToInventory(val windowSlot: Int) : ClickDecision
}

/** 箱子窗口中玩家背包区的窗口槽位范围（27 主背包 + 9 热键栏，共 36）。 */
internal fun playerInventoryWindowSlots(menuSize: Int): IntRange = menuSize until menuSize + 36

/** 窗口槽位 → Bukkit `PlayerInventory` 槽位（窗口先排主背包 9-35，再排热键栏 0-8）。 */
internal fun chestWindowSlotToBukkit(windowSlot: Int, menuSize: Int): Int {
    val rel = windowSlot - menuSize
    return if (rel < 27) rel + 9 else rel - 27
}

/** placeable 槽位要求背包可见：背包被屏蔽时玩家永远拿不起自己的物品，属静态矛盾，构建期即报错。 */
internal fun requirePlaceableVisible(hidePlayerInventory: Boolean, specs: Map<Int, SlotSpec>) {
    require(!(hidePlayerInventory && specs.values.any { it.placeable })) {
        "placeable slots require hidePlayerInventory = false: " +
            "with the player inventory hidden, players can never pick up their own items to place"
    }
}

/**
 * 点击状态机的纯决策核心：只处理 PICKUP（左/右键）模式，无副作用。
 * 输入是点击参数 + 光标 + 槽位视图快照，输出 [ClickDecision]，由引擎执行副作用。
 */
internal class ChestClickLogic(
    private val menuSize: Int,
    private val hidePlayerInventory: Boolean,
    private val hasPlaceableSlot: Boolean,
) {

    /**
     * @param menuSlot 菜单区槽位视图；点击背包区或未声明槽位时为 null
     * @param bottomItem 点击背包区时该真实槽位的物品；点击菜单区时为 null
     */
    fun decide(
        windowSlot: Int,
        rightClick: Boolean,
        cursor: VirtualCursor?,
        menuSlot: SlotView?,
        bottomItem: ItemStack?,
    ): ClickDecision {
        val inMenuArea = windowSlot < menuSize
        return when {
            cursor == null && inMenuArea -> decideEmptyCursorMenu(windowSlot, rightClick, menuSlot)
            cursor == null -> decideEmptyCursorBottom(windowSlot, bottomItem)
            inMenuArea -> decideHoldingMenu(windowSlot, rightClick, cursor, menuSlot)
            else -> decideHoldingBottom(windowSlot, cursor)
        }
    }

    private fun decideEmptyCursorMenu(slot: Int, right: Boolean, view: SlotView?): ClickDecision {
        if (view == null || !view.movable || view.item.isEmptyStack()) return ClickDecision.Deny
        val amount = view.item!!.amount
        return ClickDecision.PickupFromMenu(slot, if (right) amount - amount / 2 else amount)
    }

    private fun decideEmptyCursorBottom(windowSlot: Int, bottomItem: ItemStack?): ClickDecision {
        if (hidePlayerInventory || !hasPlaceableSlot || bottomItem.isEmptyStack()) return ClickDecision.Deny
        return ClickDecision.PickupFromInventory(windowSlot)
    }

    private fun decideHoldingMenu(slot: Int, right: Boolean, cursor: VirtualCursor, view: SlotView?): ClickDecision {
        if (view == null) return ClickDecision.Deny
        val origin = cursor.origin
        val isPutBack = origin is CursorOrigin.MenuSlot && origin.index == slot
        val fromInventory = origin is CursorOrigin.PlayerInventory
        val target = view.item
        return when {
            target.isEmptyStack() -> {
                if (!view.placeable && !isPutBack) return ClickDecision.Deny
                ClickDecision.PlaceInMenu(slot, if (right) 1 else cursor.item.amount, fromInventory)
            }
            target!!.isSimilar(cursor.item) -> {
                if (!view.placeable && !isPutBack) return ClickDecision.Deny
                val space = target.maxStackSize - target.amount
                if (space <= 0) return ClickDecision.Deny
                ClickDecision.PlaceInMenu(slot, minOf(space, if (right) 1 else cursor.item.amount), fromInventory)
            }
            else -> {
                // 异类交换：真实源不支持（v1）；菜单源要求目标可取又可放
                if (fromInventory || !view.movable || !view.placeable) return ClickDecision.Deny
                ClickDecision.SwapWithMenu(slot)
            }
        }
    }

    private fun decideHoldingBottom(windowSlot: Int, cursor: VirtualCursor): ClickDecision =
        when (val origin = cursor.origin) {
            is CursorOrigin.MenuSlot -> ClickDecision.DropToInventory(windowSlot)
            is CursorOrigin.PlayerInventory ->
                if (origin.windowSlot == windowSlot) ClickDecision.PutBackToInventory else ClickDecision.Deny
        }
}
