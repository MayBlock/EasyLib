package com.github.mayblock.easylib.impl.bukkit.util

import com.github.mayblock.easylib.packetevents.PacketManager
import com.github.mayblock.easylib.packetevents.packet.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.packet.PacketScope
import com.github.mayblock.easylib.packetevents.packet.extension.gameMode
import org.bukkit.GameMode
import org.bukkit.entity.Player

fun Player.sendPackets(
    manager: PacketManager<Player>,
    block: PacketBuilderScope.() -> Unit
) {
    manager.sendPackets(this, manager.collectPackets(block))
}

fun PacketScope.PlayerPacketScope.gameMode(gameMode: GameMode) {
    this.gameMode(com.github.retrooper.packetevents.protocol.player.GameMode.valueOf(gameMode.name))
}