package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuBuilder
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerMenuBuilder

interface MenuApi {

    fun createPlayerInventoryMenu(builder: PlayerMenuBuilder.() -> Unit): PlayerInventoryMenu
    fun createChestMenu(type: ChestMenuType, builder: ChestMenuBuilder.() -> Unit): ChestMenu
}