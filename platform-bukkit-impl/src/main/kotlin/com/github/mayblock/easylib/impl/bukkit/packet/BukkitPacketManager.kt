package com.github.mayblock.easylib.impl.bukkit.packet

import com.github.mayblock.easylib.packetevents.AbstractPacketManager
import com.github.retrooper.packetevents.PacketEvents
import org.bukkit.entity.Player

object BukkitPacketManager : AbstractPacketManager<Player>(PacketEvents.getAPI())