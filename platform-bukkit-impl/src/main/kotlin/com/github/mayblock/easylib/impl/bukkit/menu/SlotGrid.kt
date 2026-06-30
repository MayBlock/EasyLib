package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.impl.bukkit.menu.slot.LiveSlot
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.retrooper.packetevents.protocol.item.ItemStack

/**
 * 槽集合 + 共用操作，从不可变 [SlotSpec] 映射出运行态 [LiveSlot]。两类菜单共用，消除重复的 Map 处理。
 */
internal class SlotGrid(specs: Map<Int, SlotSpec>) {

    private val slots: Map<Int, LiveSlot> = specs.mapValues { LiveSlot(it.value) }

    operator fun get(index: Int): LiveSlot? = slots[index]

    /** 某个槽的 packet 物品；未定义的槽返回 [ItemStack.EMPTY]。 */
    fun packetItem(index: Int): ItemStack = slots[index]?.packetItem() ?: ItemStack.EMPTY

    /** `[0, size)` 全量 packet 物品列表，未定义处为 null（用于 WindowItems/ContainerItems）。 */
    fun packetItems(size: Int): List<ItemStack?> = List(size) { slots[it]?.packetItem() }

    fun forEachUpdatable(action: (index: Int, slot: LiveSlot) -> Unit) =
        slots.forEach { (index, slot) -> if (slot.updateRules.isNotEmpty()) action(index, slot) }
}
