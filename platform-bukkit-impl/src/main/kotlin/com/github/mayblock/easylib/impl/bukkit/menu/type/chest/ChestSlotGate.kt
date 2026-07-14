package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import org.bukkit.event.inventory.InventoryAction

/**
 * 一次点击经放行门后的决策；副作用由 [RealChestMenu] 执行。纯数据，无副作用。
 */
internal sealed interface SlotDecision {
    /** 取消该 Bukkit 事件（不可变默认）。 */
    data object Deny : SlotDecision

    /** 放行 Bukkit 原生处理，不触发任何菜单事件（如玩家整理自己背包）。 */
    data object AllowNative : SlotDecision

    /** shift-入菜单：取消原生，由引擎手动向 placeable 槽分发。 */
    data object ShiftIntoMenu : SlotDecision

    /** movable 槽被取出：触发 SlotTakeEvent；取消则阻止。 */
    data class FireTake(val slot: Int) : SlotDecision

    /** placeable 槽被放入（光标）：触发 SlotPlaceEvent；取消则阻止。 */
    data class FirePlace(val slot: Int) : SlotDecision

    /** 交换/数字键（需 movable&&placeable）：触发 take+place；任一取消则阻止。 */
    data class FireSwap(val slot: Int) : SlotDecision
}

/**
 * per-slot 放行门（纯逻辑）：默认取消，按 [InventoryAction] 与被作用 slot 的 movable/placeable 放行。
 */
internal object ChestSlotGate {

    fun decide(
        isTop: Boolean,
        rawSlot: Int,
        action: InventoryAction,
        movable: Boolean,
        placeable: Boolean,
        hidePlayerInventory: Boolean,
    ): SlotDecision {
        // 跨全库聚合 / 创造复制：无条件取消
        when (action) {
            InventoryAction.COLLECT_TO_CURSOR, InventoryAction.CLONE_STACK -> return SlotDecision.Deny
            InventoryAction.DROP_ALL_CURSOR, InventoryAction.DROP_ONE_CURSOR -> return SlotDecision.AllowNative
            InventoryAction.NOTHING, InventoryAction.UNKNOWN -> return SlotDecision.Deny
            else -> {}
        }
        if (rawSlot < 0) return SlotDecision.AllowNative // 窗口外
        return if (isTop) decideTop(rawSlot, action, movable, placeable, hidePlayerInventory) else decideBottom(action, hidePlayerInventory)
    }

    private fun decideTop(slot: Int, action: InventoryAction, movable: Boolean, placeable: Boolean, hide: Boolean): SlotDecision =
        when (action) {
            // hide=true 时玩家背包被数据包屏蔽为不可见：若仍放行 MOVE_TO_OTHER_INVENTORY，
            // 物品会被 shift 进玩家看不到的背包区（数据丢失/困惑的假象）。因此 hide=true 时
            // 无条件取消该 shift-take；其余取出方式（PICKUP/DROP 系列）不涉及跨容器移动，
            // 仍按 movable 放行。
            InventoryAction.MOVE_TO_OTHER_INVENTORY ->
                if (hide) SlotDecision.Deny
                else if (movable) SlotDecision.FireTake(slot) else SlotDecision.Deny

            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT ->
                if (movable) SlotDecision.FireTake(slot) else SlotDecision.Deny

            InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE ->
                if (placeable) SlotDecision.FirePlace(slot) else SlotDecision.Deny

            // HOTBAR_MOVE_AND_READD：Spigot 1.20.6 起服务器不再发送（并入 HOTBAR_SWAP），
            // 保留分支以兼容更早版本的运行时（相关测试已按 1.20.6 行为删除）。
            InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP, InventoryAction.HOTBAR_MOVE_AND_READD ->
                if (movable && placeable) SlotDecision.FireSwap(slot) else SlotDecision.Deny

            else -> SlotDecision.Deny
        }

    private fun decideBottom(action: InventoryAction, hide: Boolean): SlotDecision {
        if (hide) return SlotDecision.Deny
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) return SlotDecision.ShiftIntoMenu
        return SlotDecision.AllowNative
    }
}
