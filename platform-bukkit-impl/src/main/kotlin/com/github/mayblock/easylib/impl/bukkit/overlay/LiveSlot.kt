package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import com.github.retrooper.packetevents.protocol.item.ItemStack

internal class LiveSlot(private val spec: OverlaySlotSpec) {

    @Volatile
    var item: org.bukkit.inventory.ItemStack = spec.item

    val handlers: List<OverlayHandler> get() = spec.handlers
    val updateRules: List<OverlayUpdateRule> get() = spec.updateRules

    private val packetItemCache = Caffeine.newBuilder()
        .maximumSize(1)
        .build<org.bukkit.inventory.ItemStack, ItemStack> { it.fromBukkit() }

    fun packetItem(): ItemStack = packetItemCache.get(item)
}
