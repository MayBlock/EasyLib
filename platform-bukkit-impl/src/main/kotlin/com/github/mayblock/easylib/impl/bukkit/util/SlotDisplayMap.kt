package com.github.mayblock.easylib.impl.bukkit.util

import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 显示层缓存：按 (viewer, slot) 存假显示物品（spec §7）。主线程写（计算/提交），
 * netty 线程只读（出站改写查表），故用并发容器；条目不可变、整体替换。
 * menu 与 overlay 共用（与 [ViewerRegistry] 同定位）。
 *
 * 条目缺失 ⇒ 改写层透传真实物品。[commit] 在结果与真实基底相同时主动清条目，
 * 保证「规则不改 ⇒ 显示真实」与「假→真也要重绘」两个语义。
 */
internal class SlotDisplayMap {

    /** 不可变条目：bukkit 侧用于变更比较；packet 侧懒转换（生产首读在 netty，单测不触碰——LiveSlot 同精度先例）。 */
    internal class Entry(displayItem: ItemStack) {
        val bukkitItem: ItemStack = displayItem.clone()
        val packetItem: com.github.retrooper.packetevents.protocol.item.ItemStack by lazy { bukkitItem.fromBukkit() }
    }

    private val byViewer = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, Entry>>()

    /** 提交一次计算结果；返回该 viewer 对该槽的可见内容是否发生变化（脏 ⇒ 需要重绘）。 */
    fun commit(viewerId: UUID, slot: Int, base: ItemStack, displayItem: ItemStack): Boolean {
        val slots = byViewer.computeIfAbsent(viewerId) { ConcurrentHashMap() }
        val prev = slots[slot]
        return when {
            displayItem == base -> slots.remove(slot) != null // 与真实一致：清条目；有过假显示则脏
            prev?.bukkitItem == displayItem -> false
            else -> { slots[slot] = Entry(displayItem); true }
        }
    }

    /** 只读契约：返回条目的 [Entry.bukkitItem] 不做出参克隆，调用方不得改动（改动会静默污染缓存）。 */
    fun lookup(viewerId: UUID, slot: Int): Entry? = byViewer[viewerId]?.get(slot)

    /** 真实物品经原生点击变更后（新值未知）：清全 viewer 该槽，改写层透传真实，等下一次重算。 */
    fun invalidate(slot: Int) = byViewer.values.forEach { it.remove(slot) }

    fun remove(viewerId: UUID) { byViewer.remove(viewerId) }

    fun clear() = byViewer.clear()
}
