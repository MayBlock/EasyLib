package com.github.mayblock.easylib.impl.bukkit.menu.player.builder

import com.github.mayblock.easylib.api.bukkit.menu.player.InteractHandler
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerMenuItem
import com.github.mayblock.easylib.api.bukkit.menu.player.dsl.PlayerMenuScope
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

class PlayerMenuBuilder(
    private val factory: (slots: List<PlayerMenuItem?>) -> PlayerInventoryMenu
) : PlayerMenuScope {
    private val size: Int = PlayerInventoryMenu.INVENTORY_SIZE
    private val slots = MutableList<PlayerMenuItem?>(size) { null }

    override fun slot(
        slot: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        onInteract: InteractHandler?
    ) {
        require(slot in 0 until size) { "slot must be in range [0, $size]" }
        slots[slot] = item.also { item ->
            item.itemMeta = item.itemMeta?.also(metadata)
        }.let { PlayerMenuItem(it, onInteract) }
    }

    fun build(): PlayerInventoryMenu = factory(slots)
}