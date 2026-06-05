package com.github.mayblock.easylib.packetevents.packet

import com.github.mayblock.easylib.api.util.Vector
import com.github.mayblock.easylib.packetevents.annotation.PacketDsl
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityMetadataProvider
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityType
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation

@PacketDsl
interface PacketScope {

    // Packet for receive player
    fun forPlayer(block: PlayerPacketScope.() -> Unit)

    // Packet for entity
    fun forEntity(entityId: Int, block: EntityPacketScope.() -> Unit)

    // Packet for block
    fun forBlock(position: Vector<Int>, block: BlockPacketScope.() -> Unit)
    fun forBlock(position: Vector3i, block: BlockPacketScope.() -> Unit)

    // Common Packet
    fun destroyEntities(vararg entityIds: Int)

    @PacketDsl
    interface PlayerPacketScope {
        fun changeGameState(state: WrapperPlayServerChangeGameState.Reason, param: Float)
        fun camera(watchedEntityId: Int)
        fun rotation(yaw: Float, pitch: Float)
        fun containerSetSlot(windowId: Int, slot: Int, item: ItemStack?)
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
    interface BlockPacketScope {
        fun blockChange(state: WrappedBlockState)
        fun tileEntityData(blockEntityType: BlockEntityType, compound: NBTCompound)
        fun openSignEditor(isFrontText: Boolean = true)
    }
}