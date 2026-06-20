package com.github.mayblock.easylib.api.bukkit.menu.event.dsl

import com.github.mayblock.easylib.api.bukkit.menu.event.ClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.SlotClickListener
import com.github.mayblock.easylib.api.bukkit.menu.event.SlotUpdateListener
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler

@DslMarker
annotation class EventContextDsl

@EventContextDsl
interface SlotEventCollectorScope<C : ClickEvent, U : UpdateEvent> {

    val clickListeners: List<SlotClickListener<C>>
    val updateListeners: List<SlotUpdateListener<U>>
    fun onClick(block: C.() -> Unit)
    fun onUpdate(trigger: TaskScheduler.Trigger, block: U.() -> Unit)
}