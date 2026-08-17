package com.github.mayblock.easylib.base.api.bukkit

import com.github.mayblock.easylib.api.bukkit.item.CustomItemRegistry
import com.github.mayblock.easylib.api.bukkit.menu.MenuFactory
import com.github.mayblock.easylib.api.bukkit.menu.MenuRegistry
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.api.bukkit.prompt.PromptApi
import com.github.mayblock.easylib.api.bukkit.scheduler.BukkitDispatcher
import com.github.mayblock.easylib.api.bukkit.scheduler.BukkitTaskExecutors
import com.github.mayblock.easylib.base.api.EasyLibApi
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