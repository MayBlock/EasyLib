package com.github.mayblock.easylib.impl.bukkit.menu.type.player.builder

import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.bukkit.menu.type.player.InteractEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.player.dsl.PlayerMenuScope
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

internal class PlayerMenuBuilder(
    private val factory: (slots: Map<Int, SlotSpec>) -> PlayerInventoryMenu,
) : PlayerMenuScope {

    private val size: Int = PlayerInventoryMenu.INVENTORY_SIZE
    private val slots = mutableMapOf<Int, SlotSpec>()

    override fun slot(
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InteractEvent>.() -> Unit)?,
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size]" }
        slots[index] = buildSlot(item, metadata, block)
    }

    override fun slot(
        range: IntRange,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InteractEvent>.() -> Unit)?,
    ) {
        require(range.first >= 0 && range.last < size) { "slot must be in range [0, $size]" }
        val slot = buildSlot(item, metadata, block)
        range.forEach { slots[it] = slot }
    }

    private fun buildSlot(
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InteractEvent>.() -> Unit)?,
    ): SlotSpec {
        return SlotBuilder(InteractEvent::class.java)
            .apply { block?.invoke(this) }
            .build(item.also { item.itemMeta = item.itemMeta?.also(metadata) })
    }

    fun build(): PlayerInventoryMenu = factory(slots)
}
