package com.github.mayblock.easylib.impl.bukkit.menu.type.player.builder

import com.github.mayblock.easylib.api.bukkit.menu.event.Slot
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.dsl.SlotEventCollectorScope
import com.github.mayblock.easylib.api.bukkit.menu.type.player.InteractEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.player.dsl.PlayerMenuScope
import com.github.mayblock.easylib.impl.bukkit.menu.event.builder.SlotEventCollector
import com.github.mayblock.easylib.impl.bukkit.menu.internal.SlotDefinition
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

internal class PlayerMenuBuilder(
    private val factory: (slots: Map<Int, Slot>) -> PlayerInventoryMenu
) : PlayerMenuScope {
    private val size: Int = PlayerInventoryMenu.INVENTORY_SIZE
    private val slots = mutableMapOf<Int, Slot>()

    override fun slot(
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotEventCollectorScope<InteractEvent, UpdateEvent>.() -> Unit)?
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size]" }
        slots[index] = buildSlot(item, metadata, block)
    }

    override fun slot(
        range: IntRange,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotEventCollectorScope<InteractEvent, UpdateEvent>.() -> Unit)?
    ) {
        require(range.first >= 0 && range.last < size) { "slot must be in range [0, $size]" }
        val slot = buildSlot(item, metadata, block)
        range.forEach { index ->
            slots[index] = slot
        }
    }

    private fun buildSlot(
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotEventCollectorScope<InteractEvent, UpdateEvent>.() -> Unit)?
    ): Slot {
        return item.also { item ->
            item.itemMeta = item.itemMeta?.also(metadata)
        }.let { item ->
            val listener = block?.let(SlotEventCollector<InteractEvent, UpdateEvent>()::apply)
            SlotDefinition(
                item,
                UpdateEvent::class.java,
                InteractEvent::class.java,
                listener?.updateListeners ?: emptyList(),
                listener?.clickListeners ?: emptyList(),
            ).build()
        }
    }

    fun build(): PlayerInventoryMenu = factory(slots)
}