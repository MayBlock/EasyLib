package com.github.mayblock.easylib.api.bukkit

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.extension.ItemExtensionApi
import com.github.mayblock.easylib.api.bukkit.menu.MenuApi
import com.github.mayblock.easylib.api.bukkit.prompt.PromptApi

interface BukkitEasyLibApi : EasyLibApi {
    val dispatcher: BukkitDispatcher
    val promptApi: PromptApi
    val itemExtensionApi: ItemExtensionApi
    val menuApi: MenuApi
}

fun EasyLibApi.bukkitApi(): BukkitEasyLibApi = this as BukkitEasyLibApi