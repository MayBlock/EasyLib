package com.github.mayblock.easylib.platform.bukkit.impl.util

import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.packetevents.api.packet.dsl.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.api.packet.dsl.PacketScope
import com.github.mayblock.easylib.packetevents.api.packet.gameMode
import com.github.mayblock.easylib.packetevents.api.packet.sendPackets as sendPacketBatch
import com.github.retrooper.packetevents.protocol.player.User
import org.bukkit.GameMode
import org.bukkit.entity.Player

context(packetManager: PacketManager<Player>)
fun Player.sendPackets(
    block: PacketBuilderScope.() -> Unit
) {
    packetManager.sendPackets(this, packetManager.collectPackets(block))
}

context(packetManager: PacketManager<*>)
fun User.sendPackets(
    block: PacketBuilderScope.() -> Unit
) = this.sendPacketBatch(packetManager, block)

fun PacketScope.PlayerPacketScope.gameMode(gameMode: GameMode) {
    this.gameMode(com.github.retrooper.packetevents.protocol.player.GameMode.valueOf(gameMode.name))
}
