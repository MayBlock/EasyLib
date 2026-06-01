package com.github.mayblock.easylib.packetevents.packet

import com.github.mayblock.easylib.api.util.Vector
import com.github.mayblock.easylib.packetevents.packet.annotation.PacketDsl
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityMetadataProvider
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation

@PacketDsl
interface PacketScope {

    // Packet for receive player
    fun forPlayer(block: PlayerPacketScope.() -> Unit)

    // Packet for entity
    fun forEntity(entityId: Int, block: EntityPacketScope.() -> Unit)

    // Packet for sign
    fun forSign(position: Vector<Int>, block: SignPacketScope.() -> Unit)

    // Common Packet
    fun tileEntityData(position: Vector<Int>, blockEntityType: BlockEntityType, compound: NBTCompound)
    fun destroyEntities(vararg entityIds: Int)

    @PacketDsl
    interface PlayerPacketScope {
        fun changeGameState(state: WrapperPlayServerChangeGameState.Reason, param: Float)
        fun camera(watchedEntityId: Int)
        fun rotation(yaw: Float, pitch: Float)
    }

    @PacketDsl
    interface EntityPacketScope {
        fun relativeMove(vector: Vector<Double>, onGround: Boolean = false)
        fun rotation(yaw: Float, pitch: Float, onGround: Boolean = false)
        fun rotateHead(rotate: Float)
        fun passenger(vararg passengers: Int)
        fun teleport(position: Vector<Double>, yaw: Float, pitch: Float, onGround: Boolean = false)
        fun velocity(vector: Vector<Double>)
        fun status(eventId: Int)
        fun metadata(provider: EntityMetadataProvider)
        fun metadata(vararg data: EntityData<*>)
        fun equipments(vararg equipment: Equipment)
        fun animation(type: WrapperPlayServerEntityAnimation.EntityAnimationType)
    }

    @PacketDsl
    interface SignPacketScope {
        fun openSignEditor(isFrontText: Boolean = true)
        fun updateSign(vararg lines: String)
    }
}