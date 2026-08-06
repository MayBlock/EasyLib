package com.github.mayblock.easylib.api.bukkit.menu.slot.event

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

class InventoryClickEvent(
    menu: Menu,
    player: Player,
    index: Int,
    val type: ClickType
) : SlotClickEvent(menu, index, player)