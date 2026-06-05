package com.github.mayblock.easylib.impl.bukkit.util

import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib.Companion.api
import com.github.mayblock.easylib.packetevents.packet.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.packet.PacketScope
import com.github.mayblock.easylib.packetevents.util.gameMode
import com.github.mayblock.easylib.packetevents.util.sendPackets
import com.github.retrooper.packetevents.protocol.player.User
import org.bukkit.GameMode
import org.bukkit.entity.Player

fun Player.sendPackets(
    block: PacketBuilderScope.() -> Unit
) {
    val manager = api.packetManager
    manager.sendPackets(this, manager.collectPackets(block))
}

fun User.sendPackets(
    block: PacketBuilderScope.() -> Unit
) = this.sendPackets(api.packetManager, block)

fun PacketScope.PlayerPacketScope.gameMode(gameMode: GameMode) {
    this.gameMode(com.github.retrooper.packetevents.protocol.player.GameMode.valueOf(gameMode.name))
}