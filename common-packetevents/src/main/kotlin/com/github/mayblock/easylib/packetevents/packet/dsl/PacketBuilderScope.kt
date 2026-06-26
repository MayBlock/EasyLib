package com.github.mayblock.easylib.packetevents.packet.dsl

@PacketDsl
interface PacketBuilderScope : PacketScope {
    fun bundle(block: PacketScope.() -> Unit)
}