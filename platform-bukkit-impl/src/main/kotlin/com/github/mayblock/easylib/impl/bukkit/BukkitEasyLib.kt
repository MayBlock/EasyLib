package com.github.mayblock.easylib.impl.bukkit

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.BukkitEasyLibApi
import com.github.mayblock.easylib.api.bukkit.bukkitApi
import com.github.mayblock.easylib.api.bukkit.menu.MenuApi
import com.github.mayblock.easylib.api.bukkit.prompt.PromptApi
import com.github.mayblock.easylib.impl.bukkit.command.BukkitCommandRegistry
import com.github.mayblock.easylib.impl.bukkit.extension.ItemExtensionApiImpl
import com.github.mayblock.easylib.impl.bukkit.menu.MenuApiImpl
import com.github.mayblock.easylib.impl.bukkit.packet.BukkitPacketManager
import com.github.mayblock.easylib.impl.bukkit.prompt.PromptApiImpl
import com.github.mayblock.easylib.impl.bukkit.scheduler.BukkitTaskScheduler
import com.github.mayblock.easylib.packetevents.PacketManager
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import kotlin.time.Duration

class BukkitEasyLib(
    private val plugin: Plugin,
) : BukkitEasyLibApi {

    companion object {
        internal val api: BukkitEasyLib get() = EasyLibApi.api.bukkitApi() as BukkitEasyLib
    }

    override val dispatcher = BukkitDispatcherImpl(plugin)
    override val promptApi: PromptApi by lazy { PromptApiImpl }
    override val itemExtensionApi = ItemExtensionApiImpl(plugin)
    override val menuApi: MenuApi = MenuApiImpl
    override val commandRegistry = BukkitCommandRegistry(plugin)
    val packetManager: PacketManager<Player> = BukkitPacketManager

    override fun createTaskScheduler(tickPeriod: Duration) = BukkitTaskScheduler(plugin, tickPeriod)
}