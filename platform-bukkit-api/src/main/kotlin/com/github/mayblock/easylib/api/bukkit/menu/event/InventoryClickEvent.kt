package com.github.mayblock.easylib.api.bukkit.menu.event

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

class InventoryClickEvent(
    player: Player,
    slot: Int,
    val type: ClickType
) : ClickEvent(slot, player)