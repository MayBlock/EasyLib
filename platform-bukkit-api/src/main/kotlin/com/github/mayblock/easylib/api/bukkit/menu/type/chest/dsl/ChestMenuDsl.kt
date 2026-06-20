package com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl

import com.github.mayblock.easylib.api.bukkit.menu.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.dsl.SlotEventCollectorScope
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
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
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit = {},
        block: (SlotEventCollectorScope<InventoryClickEvent, UpdateEvent>.() -> Unit)? = null
    )
}

fun ChestMenuScope.slot(
    index: Int,
    type: Material,
    amount: Int = 1,
    metadata: ItemMeta.() -> Unit = {},
    block: (SlotEventCollectorScope<InventoryClickEvent, UpdateEvent>.() -> Unit)? = null
) {
    slot(index, ItemStack(type, amount), metadata, block)
}

fun ChestMenuScope.closeButton(
    index: Int,
    metadata: ItemMeta.() -> Unit = {
        setDisplayName("${ChatColor.RED}Close Menu")
    },
) {
    this.slot(index, Material.BARRIER, metadata = metadata) {
        onClick {
            this.player.closeInventory()
        }
    }
}