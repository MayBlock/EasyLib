package com.github.mayblock.easylib.packetevents.packet.extension

import com.github.mayblock.easylib.packetevents.packet.PacketScope
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState

fun PacketScope.PlayerPacketScope.gameMode(gameMode: GameMode) {
    this.changeGameState(
        WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE,
        gameMode.id.toFloat()
    )
}

fun PacketScope.EntityPacketScope.direction(yaw: Float, pitch: Float, onGround: Boolean = false) {
    this.rotation(yaw, pitch, onGround)
    this.rotateHead(yaw)
}