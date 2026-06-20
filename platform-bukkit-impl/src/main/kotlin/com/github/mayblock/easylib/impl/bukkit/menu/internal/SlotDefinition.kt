package com.github.mayblock.easylib.impl.bukkit.menu.internal

import com.github.mayblock.easylib.api.bukkit.menu.event.*
import org.bukkit.inventory.ItemStack

internal class SlotDefinition<C : ClickEvent, U : UpdateEvent>(
    val item: ItemStack,
    val updateEventClass: Class<U>,
    val clickEventClass: Class<C>,
    val updateListeners: List<SlotUpdateListener<U>>,
    val clickListeners: List<SlotClickListener<C>>
) {

    fun build(): Slot = if (updateListeners.isNotEmpty() && clickListeners.isNotEmpty()) {
        object : ClickSlot<C>, UpdatableSlot<U> {
            override var item: ItemStack = this@SlotDefinition.item
            override val updateListeners: List<SlotUpdateListener<U>> = this@SlotDefinition.updateListeners
            override val clickListeners: List<SlotClickListener<C>> = this@SlotDefinition.clickListeners
            override val clickEventClass: Class<C> = this@SlotDefinition.clickEventClass
            override val updateEventClass: Class<U> = this@SlotDefinition.updateEventClass
        }
    } else if (updateListeners.isNotEmpty()) {
        object : UpdatableSlot<U> {
            override var item: ItemStack = this@SlotDefinition.item
            override val updateEventClass: Class<U> = this@SlotDefinition.updateEventClass
            override val updateListeners: List<SlotUpdateListener<U>> = this@SlotDefinition.updateListeners
        }
    } else if (clickListeners.isNotEmpty()) {
        object : ClickSlot<C> {
            override var item: ItemStack = this@SlotDefinition.item
            override val clickEventClass: Class<C> = this@SlotDefinition.clickEventClass
            override val clickListeners: List<SlotClickListener<C>> = this@SlotDefinition.clickListeners
        }
    } else {
        object : Slot {
            override val item: ItemStack = this@SlotDefinition.item
        }
    }
}