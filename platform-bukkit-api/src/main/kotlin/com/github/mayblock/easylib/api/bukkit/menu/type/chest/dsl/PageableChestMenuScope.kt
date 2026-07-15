package com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.extensions.indexOf
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

@ChestMenuDsl
interface PageableChestMenuScope {
    val type: ChestMenuType
    fun page(title: Component = Component.text("Menu"), block: ChestMenuScope.() -> Unit)
    fun setNextPageItem(item: ItemStack, slot: Int = type.size - 4, metadata: (ItemMeta.() -> Unit)? = null)
    fun setPreviousPageItem(item: ItemStack, slot: Int = type.size - 6, metadata: (ItemMeta.() -> Unit)? = null)
}

fun PageableChestMenuScope.setNextPageItem(
    type: Material,
    amount: Int = 1,
    slot: Int = this.type.size - 4,
    metadata: (ItemMeta.() -> Unit)? = null
) = this.setNextPageItem(ItemStack(type, amount), slot, metadata)

fun PageableChestMenuScope.setPreviousPageItem(
    type: Material,
    amount: Int = 1,
    slot: Int = this.type.size - 4,
    metadata: (ItemMeta.() -> Unit)? = null
) = this.setPreviousPageItem(ItemStack(type, amount), slot, metadata)

fun PageableChestMenuScope.setNextPageItem(
    item: ItemStack,
    row: Int,
    column: Int,
    metadata: (ItemMeta.() -> Unit)? = null
) = this.setNextPageItem(item, indexOf(row, column), metadata)

fun PageableChestMenuScope.setPreviousPageItem(
    item: ItemStack,
    row: Int,
    column: Int,
    metadata: (ItemMeta.() -> Unit)? = null
) = this.setPreviousPageItem(item, indexOf(row, column), metadata)

fun PageableChestMenuScope.setNextPageItem(
    type: Material,
    row: Int,
    column: Int,
    amount: Int = 1,
    metadata: (ItemMeta.() -> Unit)? = null
) = this.setNextPageItem(ItemStack(type, amount), row, column, metadata)

fun PageableChestMenuScope.setPreviousPageItem(
    type: Material,
    row: Int,
    column: Int,
    amount: Int = 1,
    metadata: (ItemMeta.() -> Unit)? = null
) = this.setPreviousPageItem(ItemStack(type, amount), row, column, metadata)