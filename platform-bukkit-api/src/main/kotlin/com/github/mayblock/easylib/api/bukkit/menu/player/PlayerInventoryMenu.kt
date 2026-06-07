package com.github.mayblock.easylib.api.bukkit.menu.player

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.util.Destroyable
import org.bukkit.entity.Player

interface PlayerInventoryMenu : Menu, Destroyable {

    override fun open(player: Player) = activate(player)
    fun activate(player: Player)
    fun deactivate(player: Player): Boolean

    companion object {
        const val INVENTORY_SIZE = 46
    }
}