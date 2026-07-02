package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import com.github.retrooper.packetevents.protocol.item.ItemStack

/**
 * 槽的运行态：可变的当前物品 + packet 物品缓存。[SlotSpec] 的运行期对应物。
 *
 * [item] 标 `@Volatile`：更新任务在异步线程写、渲染在主线程读，需保证可见性。
 */
internal class LiveSlot(private val spec: SlotSpec) {

    @Volatile
    var item: org.bukkit.inventory.ItemStack = spec.item

    val clickHandlers: List<ClickHandler> get() = spec.clickHandlers
    val updateRules: List<UpdateRule> get() = spec.updateRules
    val movable: Boolean get() = spec.movable
    val placeable: Boolean get() = spec.placeable

    private var lastBukkitItem: org.bukkit.inventory.ItemStack? = null
    // Lazily computed on first packetItem() call — avoids triggering PacketEvents static
    // initialisation (ItemStack.EMPTY) during construction, which requires a live PacketEvents API.
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
