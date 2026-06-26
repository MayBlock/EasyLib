package com.github.mayblock.easylib.packetevents

import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.packetevents.packet.builder.PacketBuilderContext
import com.github.mayblock.easylib.packetevents.packet.dsl.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.packet.dsl.PacketCollector
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.wrapper.PacketWrapper

abstract class AbstractPacketManager<T>(
    private val packetEventsApi: PacketEventsAPI<*>
) : PacketManager<T> {

    override fun collectPackets(block: PacketBuilderScope.() -> Unit): PacketCollector {
        return PacketBuilderContext().apply(block)
    }

    override fun sendPackets(
        player: T & Any,
        packets: Collection<PacketWrapper<*>>
    ) {
        packets.forEach { packet ->
            packetEventsApi.playerManager.sendPacket(player, packet)
        }
    }

    override fun registerListener(
        listener: PacketListener,
        priority: PacketListenerPriority
    ): Disposable {
        val listener = packetEventsApi.eventManager.registerListener(listener, priority)
        return Disposable { packetEventsApi.eventManager.unregisterListener(listener) }
    }
}