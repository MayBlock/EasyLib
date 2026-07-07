package com.github.mayblock.easylib.impl.bukkit

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.BukkitEasyLibApi
import com.github.mayblock.easylib.api.bukkit.bukkitApi
import com.github.mayblock.easylib.api.bukkit.prompt.PromptApi
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.command.BukkitCommandRegistry
import com.github.mayblock.easylib.impl.bukkit.extension.ItemExtensionApiImpl
import com.github.mayblock.easylib.impl.bukkit.menu.VirtualMenuManager
import com.github.mayblock.easylib.impl.bukkit.overlay.OverlayManager
import com.github.mayblock.easylib.impl.bukkit.packet.BukkitPacketManager
import com.github.mayblock.easylib.impl.bukkit.prompt.PromptApiImpl
import com.github.mayblock.easylib.impl.bukkit.scheduler.BukkitTaskScheduler
import com.github.mayblock.easylib.packetevents.PacketManager
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class BukkitEasyLib(
    plugin: Plugin,
) : BukkitEasyLibApi {

    companion object {
        internal val api: BukkitEasyLib get() = EasyLibApi.api.bukkitApi() as BukkitEasyLib
    }

    init {
        // 让全局单例在实例化时即可用，避免接入方忘记手动赋值导致 lateinit 抛 UninitializedPropertyAccessException
        EasyLibApi.api = this
    }

    override val taskScheduler: TaskScheduler = BukkitTaskScheduler(plugin)
    override val dispatcher = BukkitDispatcherImpl(plugin)
    override val promptApi: PromptApi by lazy { PromptApiImpl }
    override val itemExtensionApi = ItemExtensionApiImpl(plugin)
    override val menuFactory = VirtualMenuManager(taskScheduler, plugin)
    override val overlayFactory = OverlayManager(taskScheduler)
    override val commandRegistry = BukkitCommandRegistry(plugin)
    val packetManager: PacketManager<Player> = BukkitPacketManager

    override fun close() {
        taskScheduler.cancelAllTasks()
        menuFactory.close()
        overlayFactory.close()
    }
}