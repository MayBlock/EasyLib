package com.github.mayblock.easylib.packetevents.api.packet.dsl

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType
import com.github.retrooper.packetevents.protocol.entity.data.EntityMetadataProvider

@DslMarker
private annotation class EntityMetadataDsl

inline fun PacketScope.EntityPacketScope.metadata(block: EntityMetadataProviderScope.() -> Unit) =
    this.metadata(EntityMetadataProviderScope().apply(block).build())

@EntityMetadataDsl
class EntityMetadataProviderScope @PublishedApi internal constructor(): Iterable<EntityData<*>> {

    private val metadata = mutableListOf<EntityData<*>>()
    override fun iterator() = metadata.iterator()

    fun addData(vararg data: EntityData<*>) {
        metadata.addAll(data)
    }

    fun <T> addData(index: Int, type: EntityDataType<T>, value: T) {
        EntityData(index, type, value).also(metadata::add)
    }

    @PublishedApi
    internal fun build() = EntityMetadataProvider { metadata }
}