package com.github.mayblock.easylib.impl.bukkit.overlay.slot

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlaySlotSpec
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlayUpdateRule
import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import com.github.retrooper.packetevents.protocol.item.ItemStack

internal class LiveSlot(private val spec: OverlaySlotSpec) {

    /**
     * 写时克隆：所有权在状态处强制——任何写入者（构造、setItem、更新循环乃至未来代码）
     * 存入的都是副本，外部残留引用改不到内部对象；配合读侧 getItem 的出参克隆，
     * item 事实上不可变（只被整体替换），packet 缓存的 key 因此稳定（Bukkit ItemStack 可变）。
     */
    @Volatile
    var item: org.bukkit.inventory.ItemStack = spec.item.clone()
        set(value) {
            field = value.clone()
        }

    val updateRules: List<OverlayUpdateRule> get() = spec.updateRules

    private val packetItemCache = Caffeine.newBuilder()
        .maximumSize(1)
        .build<org.bukkit.inventory.ItemStack, ItemStack> { it.fromBukkit() }

    fun packetItem(): ItemStack = packetItemCache.get(item)
}
