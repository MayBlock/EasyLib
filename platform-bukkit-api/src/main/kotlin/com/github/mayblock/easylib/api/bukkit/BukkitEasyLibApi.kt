package com.github.mayblock.easylib.api.bukkit

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.item.CustomItemRegistry
import com.github.mayblock.easylib.api.bukkit.menu.MenuFactory
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.api.bukkit.prompt.PromptApi
import com.github.mayblock.easylib.api.bukkit.scheduler.BukkitDispatcher
import com.github.mayblock.easylib.api.bukkit.scheduler.BukkitTaskExecutors
import java.io.Closeable

interface BukkitEasyLibApi : EasyLibApi, Closeable {
    val taskExecutors: BukkitTaskExecutors
    val dispatcher: BukkitDispatcher
    val promptApi: PromptApi
    val customItemRegistry: CustomItemRegistry
    val menuFactory: MenuFactory
    val overlayFactory: PlayerOverlayFactory
}

fun EasyLibApi.bukkitApi(): BukkitEasyLibApi = this as BukkitEasyLibApi