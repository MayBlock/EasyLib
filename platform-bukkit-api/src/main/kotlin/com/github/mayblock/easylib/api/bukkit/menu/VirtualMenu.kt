package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.api.util.Destroyable
import org.bukkit.entity.Player

interface VirtualMenu : Menu, Destroyable {
    override fun open(player: Player) = activate(player)
    fun activate(player: Player)
    fun deactivate(player: Player): Boolean
}