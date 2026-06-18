package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.chest.dsl.PageableChestMenuScope
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.player.dsl.PlayerMenuScope

interface MenuApi {

    fun createPlayerInventoryMenu(builder: PlayerMenuScope.() -> Unit): PlayerInventoryMenu
    fun createChestMenu(type: ChestMenuType, builder: PageableChestMenuScope.() -> Unit): ChestMenu
}