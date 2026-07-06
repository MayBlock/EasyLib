package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import com.github.retrooper.packetevents.protocol.item.ItemStack

/**
 * 覆盖槽的运行态：可变当前物品 + packet 物品缓存。[OverlaySlotSpec] 的运行期对应物。
 *
 * [item] 标 `@Volatile`：更新任务在异步线程写、渲染读，需保证可见性。
 */
internal class LiveSlot(private val spec: OverlaySlotSpec) {

    @Volatile
    var item: org.bukkit.inventory.ItemStack = spec.item

    val handlers: List<OverlayHandler> get() = spec.handlers
    val updateRules: List<OverlayUpdateRule> get() = spec.updateRules

    private var lastBukkitItem: org.bukkit.inventory.ItemStack? = null
    // 首次 packetItem() 时才转换——避免构造期触发 PacketEvents 静态初始化（ItemStack.EMPTY 需活的 PacketEvents API）。
    private var cachedPacketItem: ItemStack? = null

    fun packetItem(): ItemStack {
        val current = item
        if (current === lastBukkitItem) return cachedPacketItem!!
        if (current == lastBukkitItem) {
            lastBukkitItem = current
            return cachedPacketItem!!
        }
        val packet = current.fromBukkit()
        cachedPacketItem = packet
        lastBukkitItem = current
        return packet
    }
}
