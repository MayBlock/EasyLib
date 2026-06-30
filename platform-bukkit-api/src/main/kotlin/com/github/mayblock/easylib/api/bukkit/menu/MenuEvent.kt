package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.api.event.Event
import org.bukkit.entity.Player

interface MenuEvent : Event {
    val menu: Menu
}

class MenuOpenEvent(
    override val menu: Menu,
    val player: Player
): MenuEvent

class MenuCloseEvent(
    override val menu: Menu,
    val player: Player
): MenuEvent