package com.github.mayblock.easylib.base.impl.bukkit.overlay.slot

import com.github.retrooper.packetevents.protocol.item.ItemStack

/** 覆盖层槽集合：从不可变 [com.github.mayblock.easylib.base.impl.bukkit.overlay.slot.OverlaySlotSpec] 映射出运行态 [LiveSlot]。 */
internal class SlotMap(specs: Map<Int, OverlaySlotSpec>)
    : Map<Int, LiveSlot> by (specs.mapValues { LiveSlot(it.value) }) {

    /** 某槽 packet 物品；未定义返回 [ItemStack.EMPTY]。 */
    fun packetItem(index: Int): ItemStack = this[index]?.packetItem() ?: ItemStack.EMPTY

    fun forEachUpdatable(action: (index: Int, slot: LiveSlot) -> Unit) =
        this.forEach { (index, slot) -> if (slot.updateRules.isNotEmpty()) action(index, slot) }
}
