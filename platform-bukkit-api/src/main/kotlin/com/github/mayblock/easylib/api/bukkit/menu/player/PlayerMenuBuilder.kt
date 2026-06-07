package com.github.mayblock.easylib.api.bukkit.menu.player

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

class PlayerMenuBuilder(
    private val size: Int,
    private val factory: (
        slots: List<PlayerMenuItem?>
    ) -> PlayerInventoryMenu
) {
    private val _slots = MutableList<PlayerMenuItem?>(size) { null }
    val slots get() = _slots.toList()

    fun slot(
        slot: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit = {},
        onInteract: InteractHandler? = null
    ) {
        require(slot in 0 until size) { "slot must be in range [0, $size]" }
        _slots[slot] = item.also { item ->
            item.itemMeta = item.itemMeta?.also(metadata)
        }.let { PlayerMenuItem(it, onInteract) }
    }

    fun slot(
        slot: Int,
        type: Material,
        amount: Int = 1,
        metadata: ItemMeta.() -> Unit = {},
        onInteract: InteractHandler? = null
    ) {
        slot(slot, ItemStack(type, amount), metadata, onInteract)
    }

    fun build(): PlayerInventoryMenu = factory(slots)
}