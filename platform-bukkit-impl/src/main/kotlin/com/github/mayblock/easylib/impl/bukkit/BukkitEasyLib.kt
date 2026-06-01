package com.github.mayblock.easylib.impl.bukkit

import com.github.mayblock.easylib.api.bukkit.BukkitEasyLibApi
import com.github.mayblock.easylib.api.command.CommandRegistry
import com.github.mayblock.easylib.impl.CommonEasyLib
import com.github.mayblock.easylib.impl.bukkit.command.BukkitCommandRegistry
import com.github.mayblock.easylib.impl.bukkit.scheduler.BukkitTaskScheduler
import com.github.mayblock.easylib.packetevents.AbstractPacketManager
import com.github.mayblock.easylib.packetevents.PacketManager
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import kotlin.time.Duration

class BukkitEasyLib(
    private val plugin: Plugin,
    packetEventsApi: PacketEventsAPI<*> = PacketEvents.getAPI()
) : BukkitEasyLibApi, CommonEasyLib() {

    override val dispatcher = BukkitDispatcherImpl(plugin)
    override val commandRegistry: CommandRegistry = BukkitCommandRegistry(plugin)
    val packetManager: PacketManager<Player> = object : AbstractPacketManager<Player>(packetEventsApi) {}

    override fun createTaskScheduler(tickPeriod: Duration) = BukkitTaskScheduler(plugin, tickPeriod)
}