package com.github.mayblock.easylib.impl.bukkit.menu.chest.builder

import com.github.mayblock.easylib.api.bukkit.menu.ClickHandler
import com.github.mayblock.easylib.api.bukkit.menu.InventoryMenuItem
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.chest.dsl.ChestMenuScope
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

internal class ChestMenuBuilder(
    override val type: ChestMenuType,
    override var title: Component,
    private val factory: (
        title: Component, slots: List<InventoryMenuItem?>
    ) -> ChestMenu
) : ChestMenuScope {

    private val size = type.size
    private val slots = MutableList<InventoryMenuItem?>(size) { null }

    override fun slot(
        slot: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        onClick: ClickHandler?
    ) {
        require(slot in 0 until size) { "slot must be in range [0, $size]" }
        slots[slot] = item.also { item ->
            item.itemMeta = item.itemMeta?.also(metadata)
        }.let { InventoryMenuItem(it, onClick) }
    }

    fun build(): ChestMenu = factory(title, slots)
}