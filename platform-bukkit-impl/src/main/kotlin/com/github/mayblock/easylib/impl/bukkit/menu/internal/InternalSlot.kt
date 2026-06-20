package com.github.mayblock.easylib.impl.bukkit.menu.internal

import com.github.mayblock.easylib.api.bukkit.menu.event.Slot
import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import com.github.retrooper.packetevents.protocol.item.ItemStack
import org.bukkit.Material

internal class InternalSlot(val native: Slot?) {
    private var lastBukkitItem: org.bukkit.inventory.ItemStack? = null
    private var cachedPacketItem: ItemStack = ItemStack.EMPTY

    val bukkitItem: org.bukkit.inventory.ItemStack
        get() = native?.item ?: org.bukkit.inventory.ItemStack(Material.AIR)

    val packetItem: ItemStack
        get() {
            val current = bukkitItem
            if (current === lastBukkitItem) return cachedPacketItem
            if (current == lastBukkitItem) {
                lastBukkitItem = current
                return cachedPacketItem
            }
            cachedPacketItem = current.fromBukkit()
            lastBukkitItem = current
            return cachedPacketItem
        }
}