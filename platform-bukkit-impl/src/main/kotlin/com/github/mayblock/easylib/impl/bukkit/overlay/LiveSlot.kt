package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import com.github.retrooper.packetevents.protocol.item.ItemStack

/**
 * 覆盖槽的运行态：可变当前物品 + packet 物品缓存。[OverlaySlotSpec] 的运行期对应物。
 *
 * [item] 标 `@Volatile`：更新任务在异步线程写、渲染读，需保证可见性。
 * packet 物品缓存经 [VolatileMemo] 做线程安全的惰性记忆化——异步刷新线程 / 主线程 / Netty 线程
 * 并发调用 [packetItem] 时不会撕裂读或首次调用 NPE。
 */
internal class LiveSlot(private val spec: OverlaySlotSpec) {

    @Volatile
    var item: org.bukkit.inventory.ItemStack = spec.item

    val handlers: List<OverlayHandler> get() = spec.handlers
    val updateRules: List<OverlayUpdateRule> get() = spec.updateRules

    // 惰性、线程安全：compute 仅在首次 packetItem() 时调用，构造期不触发 PacketEvents 静态初始化。
    private val packetCache = VolatileMemo<org.bukkit.inventory.ItemStack, ItemStack> { it.fromBukkit() }

    fun packetItem(): ItemStack = packetCache.get(item)
}
