package com.github.mayblock.easylib.api.bukkit.menu.chest

import com.github.mayblock.easylib.api.bukkit.menu.ClickHandler
import com.github.mayblock.easylib.api.bukkit.menu.InventoryMenuItem
import net.kyori.adventure.text.Component
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

class ChestMenuBuilder(
    type: ChestMenuType,
    private val factory: (
        title: Component, slots: List<InventoryMenuItem?>
    ) -> ChestMenu
) {
    private val size = type.size
    private val _slots = MutableList<InventoryMenuItem?>(size) { null }
    val slots get() = _slots.toList()

    var title: Component = Component.text("Menu")

    fun slot(
        slot: Int,
        item: ItemStack,
        isFreeze: Boolean = true,
        metadata: ItemMeta.() -> Unit = {},
        onClick: ClickHandler? = null
    ) {
        require(slot in 0 until size) { "slot must be in range [0, $size]" }
        _slots[slot] = item.also { item ->
            item.itemMeta = item.itemMeta?.also(metadata)
        }.let { InventoryMenuItem(it, isFreeze, onClick) }
    }

    fun slot(
        slot: Int,
        type: Material,
        amount: Int = 1,
        isFreeze: Boolean = true,
        metadata: ItemMeta.() -> Unit = {},
        onClick: ClickHandler? = null
    ) {
        slot(slot, ItemStack(type, amount), isFreeze, metadata, onClick)
    }

    fun build(): ChestMenu = factory(title, slots)
}

fun ChestMenuBuilder.closeButton(
    slot: Int,
    metadata: ItemMeta.() -> Unit = {
        setDisplayName("${ChatColor.RED}Close Menu")
    },
) {
    this.slot(slot, Material.BARRIER, metadata = metadata, onClick = { player, _ ->
        player.closeInventory()
    })
}