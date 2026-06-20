package com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder

import com.github.mayblock.easylib.api.bukkit.menu.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.Slot
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.dsl.SlotEventCollectorScope
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.ChestMenuScope
import com.github.mayblock.easylib.impl.bukkit.menu.event.builder.SlotEventCollector
import com.github.mayblock.easylib.impl.bukkit.menu.internal.SlotDefinition
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

internal class ChestMenuBuilder(
    override val type: ChestMenuType,
    override var title: Component,
    private val factory: (
        title: Component, slots: Map<Int, Slot>
    ) -> ChestMenu
) : ChestMenuScope {

    private val size = type.size
    private val slots = mutableMapOf<Int, Slot>()

    override fun slot(
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (SlotEventCollectorScope<InventoryClickEvent, UpdateEvent>.() -> Unit)?
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size]" }
        slots[index] = item.also { item ->
            item.itemMeta = item.itemMeta?.also(metadata)
        }.let { item ->
            val listener = block?.let(SlotEventCollector<InventoryClickEvent, UpdateEvent>()::apply)
            SlotDefinition(
                item,
                UpdateEvent::class.java,
                InventoryClickEvent::class.java,
                listener?.updateListeners ?: emptyList(),
                listener?.clickListeners ?: emptyList()
            ).build()
        }
    }

    fun build(): ChestMenu = factory(title, slots)
}