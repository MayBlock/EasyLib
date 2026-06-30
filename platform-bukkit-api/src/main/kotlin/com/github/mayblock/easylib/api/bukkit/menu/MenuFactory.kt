package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.PageableChestMenuScope
import com.github.mayblock.easylib.api.bukkit.menu.type.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.player.dsl.PlayerMenuScope

interface MenuFactory {

    fun createPlayerInventoryMenu(builder: PlayerMenuScope.() -> Unit): PlayerInventoryMenu
    fun createChestMenu(type: ChestMenuType, builder: PageableChestMenuScope.() -> Unit): ChestMenu
}