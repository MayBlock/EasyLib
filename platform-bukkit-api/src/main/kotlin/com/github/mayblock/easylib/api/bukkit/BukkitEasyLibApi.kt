package com.github.mayblock.easylib.api.bukkit

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.extension.ItemExtension
import com.github.mayblock.easylib.api.bukkit.menu.MenuBuilder
import com.github.mayblock.easylib.api.bukkit.menu.VirtualPlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.prompt.Prompt

interface BukkitEasyLibApi : EasyLibApi {
    val dispatcher: BukkitDispatcher
    val promptApi: Prompt
    val itemExtensionApi: ItemExtension
    fun createVirtualPlayerInventory(builder: MenuBuilder<VirtualPlayerInventoryMenu>.() -> Unit): VirtualPlayerInventoryMenu
}

fun EasyLibApi.bukkitApi(): BukkitEasyLibApi = this as BukkitEasyLibApi