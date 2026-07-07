package com.github.mayblock.easylib.api.bukkit

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.extension.ItemExtensionApi
import com.github.mayblock.easylib.api.bukkit.menu.MenuFactory
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.api.bukkit.prompt.PromptApi
import java.io.Closeable

interface BukkitEasyLibApi : EasyLibApi, Closeable {
    val dispatcher: BukkitDispatcher
    val promptApi: PromptApi
    val itemExtensionApi: ItemExtensionApi
    val menuFactory: MenuFactory
    val overlayFactory: PlayerOverlayFactory
}

fun EasyLibApi.bukkitApi(): BukkitEasyLibApi = this as BukkitEasyLibApi