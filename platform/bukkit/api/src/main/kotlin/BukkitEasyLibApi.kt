package com.github.mayblock.easylib.platform.bukkit.api

import com.github.mayblock.easylib.base.api.EasyLibApi
import com.github.mayblock.easylib.platform.bukkit.api.item.CustomItemRegistry
import com.github.mayblock.easylib.platform.bukkit.api.menu.MenuFactory
import com.github.mayblock.easylib.platform.bukkit.api.menu.MenuRegistry
import com.github.mayblock.easylib.platform.bukkit.api.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.platform.bukkit.api.prompt.PromptApi
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitDispatcher
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitTaskExecutors
import java.io.Closeable

interface BukkitEasyLibApi : EasyLibApi, Closeable {
    val taskExecutors: BukkitTaskExecutors
    val dispatcher: BukkitDispatcher
    val promptApi: PromptApi
    val customItemRegistry: CustomItemRegistry
    val menuFactory: MenuFactory
    /** 菜单只读查询：某玩家当前打开的菜单、某菜单的观看者。 */
    val menuRegistry: MenuRegistry
    val overlayFactory: PlayerOverlayFactory
}

fun EasyLibApi.bukkitApi(): BukkitEasyLibApi = this as BukkitEasyLibApi