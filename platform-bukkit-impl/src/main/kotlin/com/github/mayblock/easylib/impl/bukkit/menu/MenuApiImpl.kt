package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuApi
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.chest.dsl.PageableChestMenuScope
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.player.dsl.PlayerMenuScope
import com.github.mayblock.easylib.impl.bukkit.menu.chest.VirtualChestMenu
import com.github.mayblock.easylib.impl.bukkit.menu.chest.builder.PageableChestMenuBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.player.VirtualPlayerInventoryMenu
import com.github.mayblock.easylib.impl.bukkit.menu.player.builder.PlayerMenuBuilder

object MenuApiImpl : MenuApi {

    override fun createPlayerInventoryMenu(builder: PlayerMenuScope.() -> Unit): PlayerInventoryMenu =
        PlayerMenuBuilder { slots ->
            VirtualPlayerInventoryMenu(slots)
        }.apply(builder).build()

    override fun createChestMenu(type: ChestMenuType, builder: PageableChestMenuScope.() -> Unit) =
        PageableChestMenuBuilder(type) { title, slots ->
            VirtualChestMenu(title, type, slots)
        }.apply(builder).build()
}