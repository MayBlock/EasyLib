package com.github.mayblock.easylib.packetevents.packet.extension

import com.github.mayblock.easylib.packetevents.packet.PacketScope
import com.github.mayblock.easylib.packetevents.packet.annotation.EntityMetadataDsl
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType
import com.github.retrooper.packetevents.protocol.entity.data.EntityMetadataProvider

inline fun PacketScope.EntityPacketScope.metadata(block: EntityMetadataBuilder.() -> Unit) =
    this.metadata(EntityMetadataBuilder().apply(block).build())

@EntityMetadataDsl
class EntityMetadataBuilder @PublishedApi internal constructor() {

    private val metadata = mutableListOf<EntityData<*>>()

    fun addData(vararg data: EntityData<*>) {
        metadata.addAll(data)
    }

    fun <T> addData(index: Int, type: EntityDataType<T>, value: T) {
        EntityData(index, type, value).also(metadata::add)
    }

    @PublishedApi
    internal fun build() = EntityMetadataProvider { metadata }
}