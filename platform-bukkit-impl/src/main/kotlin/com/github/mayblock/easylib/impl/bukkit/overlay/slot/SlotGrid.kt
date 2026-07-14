package com.github.mayblock.easylib.impl.bukkit.overlay.slot

import com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlaySlotSpec
import com.github.retrooper.packetevents.protocol.item.ItemStack

/** 覆盖层槽集合：从不可变 [com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlaySlotSpec] 映射出运行态 [LiveSlot]。 */
internal class SlotGrid(specs: Map<Int, OverlaySlotSpec>) {

    private val slots: Map<Int, LiveSlot> = specs.mapValues { LiveSlot(it.value) }

    operator fun get(index: Int): LiveSlot? = slots[index]

    /** 某槽 packet 物品；未定义返回 [ItemStack.EMPTY]。 */
    fun packetItem(index: Int): ItemStack = slots[index]?.packetItem() ?: ItemStack.EMPTY

    /** `[0, size)` 全量 packet 物品，未定义处为 null（用于 WindowItems/ContainerItems）。 */
    fun packetItems(size: Int): List<ItemStack?> = List(size) { slots[it]?.packetItem() }

    fun forEachUpdatable(action: (index: Int, slot: LiveSlot) -> Unit) =
        slots.forEach { (index, slot) -> if (slot.updateRules.isNotEmpty()) action(index, slot) }
}
