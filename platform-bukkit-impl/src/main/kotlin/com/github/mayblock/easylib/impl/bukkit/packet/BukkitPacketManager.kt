package com.github.mayblock.easylib.impl.bukkit.packet

import com.github.mayblock.easylib.packetevents.AbstractPacketManager
import com.github.retrooper.packetevents.PacketEvents
import org.bukkit.entity.Player

// `object` 在 JVM 上本就是首次触碰时才初始化（惰性）：只要没人在 PacketEvents 就绪前引用
// BukkitPacketManager，这里就不会提前求值。用 checkNotNull 给出明确报错，
// 而不是让 `PacketEvents.getAPI()` 返回 null 时以一句语焉不详的平台类型 NPE 失败。
object BukkitPacketManager : AbstractPacketManager<Player>(
    checkNotNull(PacketEvents.getAPI()) {
        "PacketEvents 未初始化，请确认加载顺序：BukkitPacketManager 必须在 PacketEvents API 完成 init/load 之后才能被首次访问。"
    }
)