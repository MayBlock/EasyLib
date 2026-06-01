package com.github.mayblock.easylib.packetevents

import com.github.mayblock.easylib.packetevents.packet.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.packet.PacketCollector
import com.github.mayblock.easylib.packetevents.packet.impl.PacketBuilderContext
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.wrapper.PacketWrapper

abstract class AbstractPacketManager<T>(
    private val packetEventsApi: PacketEventsAPI<*>
) : PacketManager<T> {

    override fun createPacketCollector() = PacketBuilderContext()

    override fun collectPackets(block: PacketBuilderScope.() -> Unit): PacketCollector {
        return createPacketCollector().apply(block)
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
    ): PacketListenerCommon = packetEventsApi.eventManager.registerListener(listener, priority)

    override fun unregisterListeners(listener: PacketListenerCommon) =
        packetEventsApi.eventManager.unregisterListeners(listener)
}