package com.github.mayblock.easylib.platform.bukkit.impl.menu.type.chest

import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryAction.*
import kotlin.test.Test
import kotlin.test.assertIs

class ChestSlotGateTest {

    private fun top(action: InventoryAction, declared: Boolean = false) =
        ChestSlotGate.decide(isTop = true, rawSlot = 5, action = action, declared = declared, hidePlayerInventory = false)

    private fun bottom(action: InventoryAction, hide: Boolean = false) =
        ChestSlotGate.decide(isTop = false, rawSlot = 40, action = action, declared = false, hidePlayerInventory = hide)

    // 顶部：取出（旧 movable=true ↔ 新 declared=true；movable=false ↔ declared=false）
    @Test fun `顶部 PICKUP 已声明槽放行为 FireTake`() {
        listOf(PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, DROP_ALL_SLOT, DROP_ONE_SLOT, MOVE_TO_OTHER_INVENTORY).forEach {
            assertIs<SlotDecision.FireTake>(top(it, declared = true), "$it")
            assertIs<SlotDecision.Deny>(top(it, declared = false), "$it")
        }
    }

    // 顶部：放入（旧 placeable=true ↔ 新 declared=true）
    @Test fun `顶部 PLACE 已声明槽放行为 FirePlace`() {
        listOf(PLACE_ALL, PLACE_SOME, PLACE_ONE).forEach {
            assertIs<SlotDecision.FirePlace>(top(it, declared = true), "$it")
            assertIs<SlotDecision.Deny>(top(it, declared = false), "$it")
        }
    }

    // 顶部：交换（旧需同时 movable && placeable；新只看 declared，放行/取消交给事件契约）
    @Test fun `顶部 SWAP 与 HOTBAR 已声明槽放行为 FireSwap`() {
        listOf(SWAP_WITH_CURSOR, HOTBAR_SWAP).forEach {
            assertIs<SlotDecision.FireSwap>(top(it, declared = true), "$it")
            assertIs<SlotDecision.Deny>(top(it, declared = false), "$it")
        }
    }

    // 无条件取消 / 放行
    @Test fun `双击收集与创造复制无条件取消`() {
        assertIs<SlotDecision.Deny>(top(COLLECT_TO_CURSOR, declared = true))
        assertIs<SlotDecision.Deny>(bottom(COLLECT_TO_CURSOR))
        assertIs<SlotDecision.Deny>(top(CLONE_STACK, declared = true))
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
            ChestSlotGate.decide(isTop = true, rawSlot = -999, action = PICKUP_ALL, declared = false, hidePlayerInventory = false)
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

    // 顶部 + hide=true 矩阵（BUG 回归：hide=true 时 shift-take 不应把物品移入被屏蔽的玩家背包）
    @Test fun `顶部 hide=true 时 MOVE_TO_OTHER_INVENTORY 无论是否声明均取消`() {
        listOf(true, false).forEach { declared ->
            assertIs<SlotDecision.Deny>(
                ChestSlotGate.decide(
                    isTop = true, rawSlot = 5, action = MOVE_TO_OTHER_INVENTORY,
                    declared = declared, hidePlayerInventory = true,
                ),
                "declared=$declared",
            )
        }
    }

    @Test fun `顶部 hide=true 时其他 PICKUP DROP 操作仍按声明放行`() {
        listOf(PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, DROP_ALL_SLOT, DROP_ONE_SLOT).forEach {
            assertIs<SlotDecision.FireTake>(
                ChestSlotGate.decide(isTop = true, rawSlot = 5, action = it, declared = true, hidePlayerInventory = true),
                "$it",
            )
            assertIs<SlotDecision.Deny>(
                ChestSlotGate.decide(isTop = true, rawSlot = 5, action = it, declared = false, hidePlayerInventory = true),
                "$it",
            )
        }
    }

    @Test fun `顶部 hide=false 时 MOVE_TO_OTHER_INVENTORY 按声明放行为 FireTake（行为不变）`() {
        assertIs<SlotDecision.FireTake>(
            ChestSlotGate.decide(isTop = true, rawSlot = 5, action = MOVE_TO_OTHER_INVENTORY, declared = true, hidePlayerInventory = false)
        )
        assertIs<SlotDecision.Deny>(
            ChestSlotGate.decide(isTop = true, rawSlot = 5, action = MOVE_TO_OTHER_INVENTORY, declared = false, hidePlayerInventory = false)
        )
    }
}
