package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import org.bukkit.entity.Player

internal interface VirtualMenu : Menu {
    val windowId: Int
    val activeViewers: Set<Player>
}