package com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder

import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.ChestMenuScope
import com.github.mayblock.easylib.impl.bukkit.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

internal class ChestMenuBuilder(
    override val type: ChestMenuType,
    override var title: Component,
    private val factory: (title: Component, slots: Map<Int, SlotSpec>) -> ChestMenu,
) : ChestMenuScope {

    private val size = type.size
    private val slots = mutableMapOf<Int, SlotSpec>()

    /** 该 index 是否已被用户通过 [slot] 声明；供 [PageableChestMenuBuilder] 检测与导航槽的冲突。 */
    internal fun hasSlot(index: Int): Boolean = slots.containsKey(index)

    override fun slot(
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size)" }
        slots[index] = buildSlot(item, metadata, block)
    }

    override fun slot(
        range: IntRange,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ) {
        require(range.first >= 0 && range.last < size) { "slot must be in range [0, $size)" }
        val slot = buildSlot(item, metadata, block)
        range.forEach { slots[it] = slot }
    }

    private fun buildSlot(
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ): SlotSpec {
        // 先 clone 再改 meta：避免直接篡改调用方传入的 item 实例。
        // 用 ?.also 而非 ItemStackExt.meta()：后者对 AIR 物品（空槽常见写法）会抛异常，
        // 这里需要对无 meta 的物品（如 AIR）静默跳过，保留既有正确行为。
        return SlotBuilder(InventoryClickEvent::class.java)
            .apply { block?.invoke(this) }
            .build(item.clone().also { it.itemMeta = it.itemMeta?.also(metadata) })
    }

    fun build(): ChestMenu = factory(title, slots)
}
