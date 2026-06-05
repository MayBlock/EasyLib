package com.github.mayblock.easylib.packetevents.packet

import com.github.mayblock.easylib.packetevents.annotation.PacketDsl

@PacketDsl
interface PacketBuilderScope : PacketScope {
    fun bundle(block: PacketScope.() -> Unit)
}