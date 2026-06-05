package com.github.mayblock.easylib.packetevents.util

import com.github.mayblock.easylib.packetevents.PacketManager
import com.github.mayblock.easylib.packetevents.packet.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.packet.PacketScope
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTList
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.protocol.nbt.NBTType
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes
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

fun PacketScope.BlockPacketScope.updateSign(
    vararg lines: String?,
    isFrontText: Boolean = true,
) {
    val sign = NBTCompound()
    val textCompound = NBTCompound().apply {
        val tags = lines.take(4).map { line ->
            NBTString(line ?: "")
        }
        setTag("messages", NBTList(NBTType.STRING, tags))
    }
    sign.setTag(if (isFrontText) "front_text" else "back_text", textCompound)
    tileEntityData(BlockEntityTypes.SIGN, sign)
}

fun User.sendPackets(
    manager: PacketManager<*>,
    block: PacketBuilderScope.() -> Unit
) {
    manager.collectPackets(block).forEach {
        this.sendPacket(it)
    }
}