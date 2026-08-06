package com.github.mayblock.easylib.packetevents.api.packet.dsl

@PacketDsl
interface PacketBuilderScope : PacketScope {
    fun bundle(block: PacketScope.() -> Unit)
}