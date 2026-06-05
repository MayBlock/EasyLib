package com.github.mayblock.easylib.api.bukkit.menu

import org.bukkit.entity.Player

interface Menu {
    val size: Int
    val slots: Map<Int, MenuItem>
    fun open(player: Player)
}