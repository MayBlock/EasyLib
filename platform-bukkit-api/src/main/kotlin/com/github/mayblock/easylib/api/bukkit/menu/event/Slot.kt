package com.github.mayblock.easylib.api.bukkit.menu.event

import org.bukkit.inventory.ItemStack

interface Slot {
    val item: ItemStack
}

interface ClickSlot<E : ClickEvent> : Slot {
    val clickEventClass: Class<out E>
    val clickListeners: List<SlotClickListener<E>>
}

interface UpdatableSlot<E : UpdateEvent> : Slot {
    override var item: ItemStack
    val updateEventClass: Class<out E>
    val updateListeners: List<SlotUpdateListener<E>>
}