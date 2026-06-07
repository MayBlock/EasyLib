package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuApi
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuBuilder
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerMenuBuilder

object MenuApiImpl : MenuApi {

    override fun createPlayerInventoryMenu(builder: PlayerMenuBuilder.() -> Unit) =
        PlayerMenuBuilder(PlayerInventoryMenu.INVENTORY_SIZE) { slots ->
            VirtualPlayerInventoryMenu(slots)
        }.apply(builder).build()

    override fun createChestMenu(type: ChestMenuType, builder: ChestMenuBuilder.() -> Unit) =
        ChestMenuBuilder(type) { title, slots ->
            VirtualChestMenu(title, type, slots)
        }.apply(builder).build()
}