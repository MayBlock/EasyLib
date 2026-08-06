package com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl

import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.item
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.extensions.indexOf
import net.kyori.adventure.text.Component
import org.bukkit.ChatColor
import org.bukkit.Material
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
        block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
    )

    fun slot(
        range: IntRange,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
    )
}

fun ChestMenuScope.slot(
    row: Int,
    column: Int,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) = slot(indexOf(row, column), block)

fun ChestMenuScope.slot(
    rows: IntRange,
    column: Int,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) = slot(indexOf(rows, column..column), block)

fun ChestMenuScope.slot(
    row: Int,
    columns: IntRange,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) = slot(indexOf(row..row, columns), block)

fun ChestMenuScope.slot(
    rows: IntRange,
    columns: IntRange,
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) = slot(indexOf(rows, columns), block)

fun ChestMenuScope.closeButton(
    index: Int,
    metadata: ItemMeta.() -> Unit = {
        setDisplayName("${ChatColor.RED}Close Menu")
    },
) = slot(index) {
    item(Material.BARRIER, metadata = metadata)
    onClick {
        this.player.closeInventory()
    }
}

