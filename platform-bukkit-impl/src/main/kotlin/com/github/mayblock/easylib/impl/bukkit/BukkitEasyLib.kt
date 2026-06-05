package com.github.mayblock.easylib.impl.bukkit

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.BukkitEasyLibApi
import com.github.mayblock.easylib.api.bukkit.bukkitApi
import com.github.mayblock.easylib.api.bukkit.menu.MenuBuilder
import com.github.mayblock.easylib.api.bukkit.menu.VirtualPlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.prompt.Prompt
import com.github.mayblock.easylib.impl.bukkit.command.BukkitCommandRegistry
import com.github.mayblock.easylib.impl.bukkit.extension.ItemExtensionImpl
import com.github.mayblock.easylib.impl.bukkit.menu.VirtualPlayerInventoryMenuImpl
import com.github.mayblock.easylib.impl.bukkit.packet.BukkitPacketManager
import com.github.mayblock.easylib.impl.bukkit.prompt.PromptImpl
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
    override val promptApi: Prompt by lazy { PromptImpl }
    override val itemExtensionApi = ItemExtensionImpl(plugin)
    override val commandRegistry = BukkitCommandRegistry(plugin)
    val packetManager: PacketManager<Player> = BukkitPacketManager

    override fun createVirtualPlayerInventory(builder: MenuBuilder<VirtualPlayerInventoryMenu>.() -> Unit): VirtualPlayerInventoryMenu =
        MenuBuilder<VirtualPlayerInventoryMenu>(VirtualPlayerInventoryMenu.INVENTORY_SIZE) { slots ->
            VirtualPlayerInventoryMenuImpl(slots)
        }.apply(builder).build()

    override fun createTaskScheduler(tickPeriod: Duration) = BukkitTaskScheduler(plugin, tickPeriod)
}