package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuApi
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.PageableChestMenuScope
import com.github.mayblock.easylib.api.bukkit.menu.type.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.player.dsl.PlayerMenuScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.VirtualChestMenu
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder.PageableChestMenuBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.type.player.VirtualPlayerInventoryMenu
import com.github.mayblock.easylib.impl.bukkit.menu.type.player.builder.PlayerMenuBuilder

class MenuApiImpl(private val taskScheduler: TaskScheduler) : MenuApi {

    override fun createPlayerInventoryMenu(builder: PlayerMenuScope.() -> Unit): PlayerInventoryMenu =
        PlayerMenuBuilder { slots ->
            VirtualPlayerInventoryMenu(taskScheduler, slots)
        }.apply(builder).build()

    override fun createChestMenu(type: ChestMenuType, builder: PageableChestMenuScope.() -> Unit) =
        PageableChestMenuBuilder(type) { title, slots ->
            VirtualChestMenu(taskScheduler, title, type, slots)
        }.apply(builder).build()
}