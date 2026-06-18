package com.github.mayblock.easylib.api.bukkit.menu.chest.dsl

import com.github.mayblock.easylib.api.bukkit.menu.ClickHandler
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuType
import net.kyori.adventure.text.Component
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

@DslMarker
annotation class ChestMenuDsl

@ChestMenuDsl
interface PageableChestMenuScope {
    val type: ChestMenuType
    fun page(title: Component = Component.text("Menu"), block: ChestMenuScope.() -> Unit)
    fun setNextPageItem(item: ItemStack, slot: Int = type.size - 4, metadata: ItemMeta.() -> Unit = {})
    fun setPreviousPageItem(item: ItemStack, slot: Int = type.size - 6, metadata: ItemMeta.() -> Unit = {})
}

@ChestMenuDsl
interface ChestMenuScope {
    var title: Component
    val type: ChestMenuType
    fun slot(
        slot: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit = {},
        onClick: ClickHandler? = null
    )
}

fun ChestMenuScope.slot(
    slot: Int,
    type: Material,
    amount: Int = 1,
    metadata: ItemMeta.() -> Unit = {},
    onClick: ClickHandler? = null
) {
    slot(slot, ItemStack(type, amount), metadata, onClick)
}

fun ChestMenuScope.closeButton(
    slot: Int,
    metadata: ItemMeta.() -> Unit = {
        setDisplayName("${ChatColor.RED}Close Menu")
    },
) {
    this.slot(slot, Material.BARRIER, metadata = metadata, onClick = { player, _ ->
        player.closeInventory()
    })
}