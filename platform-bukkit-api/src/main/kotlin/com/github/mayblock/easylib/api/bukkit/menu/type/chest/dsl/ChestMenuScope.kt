package com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl

import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.extensions.indexOf
import net.kyori.adventure.text.Component
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

@ChestMenuDsl
interface ChestMenuScope {
    var title: Component
    val type: ChestMenuType

    /**
     * 声明一个槽位。取出/放入不再由静态布尔配置，而是事件契约：[SlotTakeEvent]/[SlotPlaceEvent]
     * 默认取消，声明 `onTake`/`onPlace` 并显式 `isCancelled = false` 才放行（见两事件类 KDoc）。
     * 放入类操作要求菜单以 `hidePlayerInventory = false` 创建。
     */
    fun slot(
        index: Int,
        item: ItemStack,
        metadata: (ItemMeta.() -> Unit)? = null,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
    )

    fun slot(
        range: IntRange,
        item: ItemStack,
        metadata: (ItemMeta.() -> Unit)? = null,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
    )
}

fun ChestMenuScope.slot(
    index: Int,
    type: Material,
    amount: Int = 1,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(index, ItemStack(type, amount), metadata, block)
}

fun ChestMenuScope.slot(
    range: IntRange,
    type: Material,
    amount: Int = 1,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(range, ItemStack(type, amount), metadata, block)
}

fun ChestMenuScope.slot(
    row: Int,
    column: Int,
    item: ItemStack,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(indexOf(row, column), item, metadata, block)
}

fun ChestMenuScope.slot(
    row: Int,
    column: Int,
    type: Material,
    amount: Int = 1,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(row, column, ItemStack(type, amount), metadata, block)
}

fun ChestMenuScope.slot(
    rows: IntRange,
    column: Int,
    item: ItemStack,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(indexOf(rows, column..column), item, metadata, block)
}

fun ChestMenuScope.slot(
    row: Int,
    columns: IntRange,
    item: ItemStack,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(indexOf(row..row, columns), item, metadata, block)
}

fun ChestMenuScope.slot(
    rows: IntRange,
    columns: IntRange,
    item: ItemStack,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(indexOf(rows, columns), item, metadata, block)
}

fun ChestMenuScope.slot(
    rows: IntRange,
    column: Int,
    type: Material,
    amount: Int = 1,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(rows, column, ItemStack(type, amount), metadata, block)
}

fun ChestMenuScope.slot(
    row: Int,
    columns: IntRange,
    type: Material,
    amount: Int = 1,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(row, columns, ItemStack(type, amount), metadata, block)
}

fun ChestMenuScope.slot(
    rows: IntRange,
    columns: IntRange,
    type: Material,
    amount: Int = 1,
    metadata: (ItemMeta.() -> Unit)? = null,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(rows, columns, ItemStack(type, amount), metadata, block)
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

