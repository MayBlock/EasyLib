package com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl

import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
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

    /**
     * 声明一个槽位。
     * @param movable 槽中物品可被玩家拿起（取出前经 `onTake` 把关，见 [SlotTakeEvent]；物品移动由原生完成）
     * @param placeable 玩家可把自己背包的物品放入本槽（放入前经 `onPlace` 把关，见 [SlotPlaceEvent]；
     *   要求菜单以 `hidePlayerInventory = false` 创建）
     */
    fun slot(
        index: Int,
        item: ItemStack,
        movable: Boolean = false,
        placeable: Boolean = false,
        metadata: ItemMeta.() -> Unit = {},
        block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
    )
    fun slot(
        range: IntRange,
        item: ItemStack,
        movable: Boolean = false,
        placeable: Boolean = false,
        metadata: ItemMeta.() -> Unit = {},
        block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
    )
}

fun ChestMenuScope.slot(
    index: Int,
    type: Material,
    amount: Int = 1,
    movable: Boolean = false,
    placeable: Boolean = false,
    metadata: ItemMeta.() -> Unit = {},
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(index, ItemStack(type, amount), movable, placeable, metadata, block)
}
fun ChestMenuScope.slot(
    range: IntRange,
    type: Material,
    amount: Int = 1,
    movable: Boolean = false,
    placeable: Boolean = false,
    metadata: ItemMeta.() -> Unit = {},
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(range, ItemStack(type, amount), movable, placeable, metadata, block)
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