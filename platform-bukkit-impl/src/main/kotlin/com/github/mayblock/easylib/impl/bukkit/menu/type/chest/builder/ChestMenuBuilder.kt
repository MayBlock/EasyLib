package com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder

import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.ChestMenuScope
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
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

    override fun slot(
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size]" }
        slots[index] = buildSlot(item, metadata, block)
    }

    override fun slot(
        range: IntRange,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ) {
        require(range.first >= 0 && range.last < size) { "slot must be in range [0, $size]" }
        val slot = buildSlot(item, metadata, block)
        range.forEach { slots[it] = slot }
    }

    private fun buildSlot(
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ): SlotSpec {
        return SlotBuilder(InventoryClickEvent::class.java)
            .apply { block?.invoke(this) }
            .build(item.also { item.itemMeta = item.itemMeta?.also(metadata) })
    }

    fun build(): ChestMenu = factory(title, slots)
}
