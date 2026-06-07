package com.github.mayblock.easylib.api.bukkit.menu.chest

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import net.kyori.adventure.text.Component

interface ChestMenu : Menu {
    val title: Component
    val type: ChestMenuType
}