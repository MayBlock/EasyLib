package com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event

import com.github.mayblock.easylib.platform.bukkit.api.menu.Menu
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

class InventoryClickEvent(
    menu: Menu,
    player: Player,
    index: Int,
    val type: ClickType
) : SlotClickEvent(menu, index, player)