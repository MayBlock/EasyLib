package com.github.mayblock.easylib.platform.bukkit.impl.menu.type.chest.builder

import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.dsl.ChestMenuScope
import com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.SlotSpec
import com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.builder.SlotBuilder
import net.kyori.adventure.text.Component

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
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size)" }
        slots[index] = buildSlot(block)
    }

    override fun slot(
        range: IntRange,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ) {
        require(range.first >= 0 && range.last < size) { "slot must be in range [0, $size)" }
        val slot = buildSlot(block)
        range.forEach { slots[it] = slot }
    }

    private fun buildSlot(
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java)
            .apply { block?.invoke(this) }
            .build()

    fun build(): ChestMenu = factory(title, slots)
}
