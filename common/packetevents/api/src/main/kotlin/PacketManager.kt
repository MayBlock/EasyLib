package com.github.mayblock.easylib.packetevents.api

import com.github.mayblock.easylib.base.api.util.Disposable
import com.github.mayblock.easylib.packetevents.api.packet.dsl.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.api.packet.dsl.PacketCollector
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.wrapper.PacketWrapper

interface PacketManager<T> {
    fun collectPackets(block: PacketBuilderScope.() -> Unit): PacketCollector
    fun sendPackets(player: T & Any, packets: Collection<PacketWrapper<*>>)
    fun registerListener(
        listener: PacketListener,
        priority: PacketListenerPriority = PacketListenerPriority.NORMAL
    ): Disposable
}