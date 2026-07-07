package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryAction.*
import kotlin.test.Test
import kotlin.test.assertIs

class ChestSlotGateTest {

    private fun top(action: InventoryAction, movable: Boolean = false, placeable: Boolean = false) =
        ChestSlotGate.decide(isTop = true, rawSlot = 5, action = action, movable = movable, placeable = placeable, hidePlayerInventory = false)

    private fun bottom(action: InventoryAction, hide: Boolean = false) =
        ChestSlotGate.decide(isTop = false, rawSlot = 40, action = action, movable = false, placeable = false, hidePlayerInventory = hide)

    // 顶部：取出
    @Test fun `顶部 PICKUP movable 放行为 FireTake`() {
        listOf(PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, DROP_ALL_SLOT, DROP_ONE_SLOT, MOVE_TO_OTHER_INVENTORY).forEach {
            assertIs<SlotDecision.FireTake>(top(it, movable = true), "$it")
            assertIs<SlotDecision.Deny>(top(it, movable = false), "$it")
        }
    }

    // 顶部：放入
    @Test fun `顶部 PLACE placeable 放行为 FirePlace`() {
        listOf(PLACE_ALL, PLACE_SOME, PLACE_ONE).forEach {
            assertIs<SlotDecision.FirePlace>(top(it, placeable = true), "$it")
            assertIs<SlotDecision.Deny>(top(it, placeable = false), "$it")
        }
    }

    // 顶部：交换（需 movable && placeable）
    @Test fun `顶部 SWAP 与 HOTBAR 需同时 movable 与 placeable`() {
        listOf(SWAP_WITH_CURSOR, HOTBAR_SWAP).forEach {
            assertIs<SlotDecision.FireSwap>(top(it, movable = true, placeable = true), "$it")
            assertIs<SlotDecision.Deny>(top(it, movable = true, placeable = false), "$it")
            assertIs<SlotDecision.Deny>(top(it, movable = false, placeable = true), "$it")
        }
    }

    // 无条件取消 / 放行
    @Test fun `双击收集与创造复制无条件取消`() {
        assertIs<SlotDecision.Deny>(top(COLLECT_TO_CURSOR, movable = true, placeable = true))
        assertIs<SlotDecision.Deny>(bottom(COLLECT_TO_CURSOR))
        assertIs<SlotDecision.Deny>(top(CLONE_STACK, movable = true, placeable = true))
    }

    @Test fun `丢弃光标物品放行原生`() {
        assertIs<SlotDecision.AllowNative>(top(DROP_ALL_CURSOR))
        assertIs<SlotDecision.AllowNative>(top(DROP_ONE_CURSOR))
    }

    @Test fun `NOTHING 与 UNKNOWN 取消`() {
        assertIs<SlotDecision.Deny>(top(NOTHING))
        assertIs<SlotDecision.Deny>(top(UNKNOWN))
    }

    @Test fun `窗口外点击放行原生`() {
        assertIs<SlotDecision.AllowNative>(
            ChestSlotGate.decide(isTop = true, rawSlot = -999, action = PICKUP_ALL, movable = false, placeable = false, hidePlayerInventory = false)
        )
    }

    // 底部
    @Test fun `底部 hide 时一律取消`() {
        listOf(PICKUP_ALL, PLACE_ALL, SWAP_WITH_CURSOR, MOVE_TO_OTHER_INVENTORY).forEach {
            assertIs<SlotDecision.Deny>(bottom(it, hide = true), "$it")
        }
    }

    @Test fun `底部非 hide shift 入菜单为 ShiftIntoMenu`() {
        assertIs<SlotDecision.ShiftIntoMenu>(bottom(MOVE_TO_OTHER_INVENTORY, hide = false))
    }

    @Test fun `底部非 hide 普通操作放行原生`() {
        listOf(PICKUP_ALL, PLACE_ALL, SWAP_WITH_CURSOR, DROP_ALL_SLOT).forEach {
            assertIs<SlotDecision.AllowNative>(bottom(it, hide = false), "$it")
        }
    }
}
