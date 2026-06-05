package com.github.mayblock.easylib.api.bukkit.menu

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

class MenuBuilder<T : Menu>(
    private val size: Int,
    private val factory: (
        slots: Map<Int, MenuItem>
    ) -> T
) {
    private val _slots = mutableMapOf<Int, MenuItem>()
    val slots get() = _slots.toMap()

    fun item(
        slot: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit = {},
        onClick: ClickHandler
    ) {
        require(slot in 0 until size) { "slot must be in range [0, $size]" }
        _slots[slot] = item.also { item ->
            item.itemMeta = item.itemMeta?.also(metadata)
        }.let { MenuItem(it, onClick) }
    }

    fun item(
        slot: Int,
        type: Material,
        amount: Int = 1,
        metadata: ItemMeta.() -> Unit = {},
        onClick: ClickHandler
    ) {
        item(slot, ItemStack(type, amount), metadata, onClick)
    }

    fun build(): T = factory(slots)
}
