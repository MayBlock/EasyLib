package com.github.mayblock.easylib.platform.bukkit.impl.util

import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.packetevents.api.packet.dsl.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.api.packet.dsl.PacketCollector
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import kotlin.test.Test

class PacketExtTest {

    @Test
    fun `context packet manager collects and sends the same batch`() {
        val player = mockk<Player>()
        val packets = mockk<PacketCollector>()
        val manager = mockk<PacketManager<Player>>()
        every { manager.collectPackets(any()) } returns packets
        justRun { manager.sendPackets(player, packets) }

        context(manager) {
            player.sendPackets { }
        }

        verify(exactly = 1) { manager.collectPackets(any<PacketBuilderScope.() -> Unit>()) }
        verify(exactly = 1) { manager.sendPackets(player, packets) }
    }
}
