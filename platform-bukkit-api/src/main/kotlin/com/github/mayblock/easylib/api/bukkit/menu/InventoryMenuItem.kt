package com.github.mayblock.easylib.api.bukkit.menu

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

typealias ClickHandler = (Player, ClickType) -> Unit

data class InventoryMenuItem(
    val item: ItemStack,
    val isFreeze: Boolean,
    val onClick: ClickHandler?
)