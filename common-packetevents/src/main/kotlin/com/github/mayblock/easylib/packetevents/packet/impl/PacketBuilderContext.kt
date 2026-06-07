package com.github.mayblock.easylib.packetevents.packet.impl

import com.github.mayblock.easylib.api.util.Vector
import com.github.mayblock.easylib.packetevents.packet.ContainerType
import com.github.mayblock.easylib.packetevents.packet.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.packet.PacketCollector
import com.github.mayblock.easylib.packetevents.packet.PacketScope
import com.github.mayblock.easylib.packetevents.util.toVector3d
import com.github.mayblock.easylib.packetevents.util.toVector3i
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityMetadataProvider
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityType
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.*
import net.kyori.adventure.text.Component

class PacketBuilderContext : PacketCollector, PacketBuilderScope {

    private val packets = mutableListOf<PacketWrapper<*>>()

    override fun contains(element: PacketWrapper<*>) = packets.contains(element)
    override fun containsAll(elements: Collection<PacketWrapper<*>>) = packets.containsAll(elements)
    override fun isEmpty() = packets.isEmpty()
    override fun iterator() = packets.iterator()
    override val size: Int get() = packets.size

    override fun forPlayer(block: PacketScope.PlayerPacketScope.() -> Unit) {
        PlayerPacketFactory { this.collect() }.apply(block)
    }

    override fun forEntity(entityId: Int, block: PacketScope.EntityPacketScope.() -> Unit) {
        EntityPacketFactory(entityId) { this.collect() }.apply(block)
    }

    override fun forBlock(position: Vector<Int>, block: PacketScope.BlockPacketScope.() -> Unit) =
        forBlock(position.toVector3i(), block)

    override fun forBlock(position: Vector3i, block: PacketScope.BlockPacketScope.() -> Unit) {
        BlockPacketFactory(position) { this.collect() }.apply(block)
    }

    override fun destroyEntities(vararg entityIds: Int) {
        WrapperPlayServerDestroyEntities(*entityIds).collect()
    }

    override fun bundle(block: PacketScope.() -> Unit) {
        val children = PacketBuilderContext().apply(block)
        WrapperPlayServerBundle().collect()
        children.forEach { it.collect() }
        WrapperPlayServerBundle().collect()
    }

    class PlayerPacketFactory internal constructor(
        private val collect: PacketWrapper<*>.() -> Unit
    ) : PacketScope.PlayerPacketScope {

        override fun changeGameState(state: WrapperPlayServerChangeGameState.Reason, param: Float) {
            WrapperPlayServerChangeGameState(state, param).collect()
        }

        override fun camera(watchedEntityId: Int) {
            WrapperPlayServerCamera(watchedEntityId).collect()
        }

        override fun rotation(yaw: Float, pitch: Float) {
            WrapperPlayServerPlayerRotation(yaw, pitch).collect()
        }

        override fun containerSetSlot(windowId: Int, stateId: Int, slot: Int, item: ItemStack?) {
            WrapperPlayServerSetSlot(windowId, stateId, slot, item ?: ItemStack.EMPTY).collect()
        }

        override fun containerOpen(windowId: Int, type: ContainerType, title: Component) {
            WrapperPlayServerOpenWindow(windowId, type.id, title).collect()
        }

        override fun containerItems(windowId: Int, stateId: Int, items: List<ItemStack?>, carriedItem: ItemStack?) {
            WrapperPlayServerWindowItems(windowId, stateId, items.map { it ?: ItemStack.EMPTY }, carriedItem).collect()
        }
    }

    class EntityPacketFactory internal constructor(
        private val entityId: Int,
        private val collect: PacketWrapper<*>.() -> Unit
    ) : PacketScope.EntityPacketScope {

        override fun relativeMove(vector: Vector<Double>, onGround: Boolean) {
            WrapperPlayServerEntityRelativeMove(
                entityId,
                vector.x,
                vector.y,
                vector.z,
                onGround
            ).collect()
        }

        override fun rotation(yaw: Float, pitch: Float, onGround: Boolean) {
            WrapperPlayServerEntityRotation(entityId, yaw, pitch, onGround).collect()
        }

        override fun rotateHead(rotate: Float) {
            WrapperPlayServerEntityHeadLook(entityId, rotate).collect()
        }

        override fun passenger(vararg passengers: Int) {
            WrapperPlayServerSetPassengers(entityId, passengers).collect()
        }

        override fun teleport(position: Vector<Double>, yaw: Float, pitch: Float, onGround: Boolean) {
            //WrapperPlayServerEntityPositionSync
            val loc = Location(
                position.toVector3d(),
                yaw,
                pitch,
            )
            WrapperPlayServerEntityTeleport(entityId, loc, onGround).collect()
        }

        override fun velocity(vector: Vector<Double>) {
            WrapperPlayServerEntityVelocity(entityId, vector.toVector3d()).collect()
        }

        override fun status(eventId: Int) {
            WrapperPlayServerEntityStatus(entityId, eventId).collect()
        }

        override fun metadata(provider: EntityMetadataProvider) {
            WrapperPlayServerEntityMetadata(entityId, provider).collect()
        }

        override fun metadata(vararg data: EntityData<*>) {
            WrapperPlayServerEntityMetadata(entityId, data.toList()).collect()
        }

        override fun equipments(vararg equipment: Equipment) {
            WrapperPlayServerEntityEquipment(entityId, equipment.toList())
        }

        override fun animation(type: WrapperPlayServerEntityAnimation.EntityAnimationType) {
            WrapperPlayServerEntityAnimation(entityId, type).collect()
        }
    }

    class BlockPacketFactory internal constructor(
        private val position: Vector3i,
        private val collect: PacketWrapper<*>.() -> Unit
    ) : PacketScope.BlockPacketScope {

        override fun blockChange(state: WrappedBlockState) {
            WrapperPlayServerBlockChange(position, state).collect()
        }

        override fun tileEntityData(
            blockEntityType: BlockEntityType,
            compound: NBTCompound
        ) {
            WrapperPlayServerBlockEntityData(
                position,
                blockEntityType,
                compound
            ).collect()
        }

        override fun openSignEditor(isFrontText: Boolean) {
            WrapperPlayServerOpenSignEditor(position, isFrontText).collect()
        }
    }

    private fun PacketWrapper<*>.collect() = also(packets::add)
}