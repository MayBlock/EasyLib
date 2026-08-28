package com.github.mayblock.easylib.platform.bukkit.impl.menu.type.chest

import com.github.mayblock.easylib.platform.bukkit.impl.util.isEmptyStack
import org.bukkit.inventory.ItemStack

/** 一次 shift-入菜单向某候选槽放入的量。 */
internal data class Placement(val slot: Int, val amount: Int)

/**
 * shift-入菜单的分发计划（纯逻辑）：把 [source] 按 slot 顺序**只向候选槽**（声明了 onPlace
 * 放行处理器的槽，见 [RealChestMenu.handleShiftIntoMenu]）分发，先填同类未满堆叠、再填空槽，
 * 遵守 `maxStackSize`，总量不超过 `source.amount`。
 */
internal object ShiftIntoMenuPlanner {

    fun plan(source: ItemStack, candidateSlots: List<Pair<Int, ItemStack?>>): List<Placement> {
        var remaining = source.amount
        if (remaining <= 0) return emptyList()
        val max = source.maxStackSize
        val result = mutableListOf<Placement>()

        // 第一轮：填同类未满堆叠
        for ((index, current) in candidateSlots) {
            if (remaining <= 0) break
            if (current == null || current.isEmptyStack()) continue
            if (!current.isSimilar(source)) continue
            val space = max - current.amount
            if (space <= 0) continue
            val put = minOf(space, remaining)
            result += Placement(index, put)
            remaining -= put
        }
        // 第二轮：填空槽
        for ((index, current) in candidateSlots) {
            if (remaining <= 0) break
            if (!(current == null || current.isEmptyStack())) continue
            val put = minOf(max, remaining)
            result += Placement(index, put)
            remaining -= put
        }
        return result
    }
}
