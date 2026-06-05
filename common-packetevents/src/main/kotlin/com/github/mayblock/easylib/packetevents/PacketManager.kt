package com.github.mayblock.easylib.packetevents

import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.packetevents.packet.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.packet.PacketCollector
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.wrapper.PacketWrapper

interface PacketManager<T> {
    fun createPacketCollector(): PacketCollector
    fun collectPackets(block: PacketBuilderScope.() -> Unit): PacketCollector
    fun sendPackets(player: T & Any, packets: Collection<PacketWrapper<*>>)
    fun registerListener(
        listener: PacketListener,
        priority: PacketListenerPriority = PacketListenerPriority.NORMAL
    ): Disposable
}