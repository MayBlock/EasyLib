package com.github.mayblock.easylib.platform.bukkit.api.menu

import org.bukkit.entity.Player

interface MenuRegistry {

    fun getActiveMenu(player: Player): Menu?
    fun hasActiveMenu(player: Player): Boolean
    fun getViewers(menu: Menu): Set<Player>
}