package com.github.mayblock.easylib.impl.bukkit.menu.event.builder

import com.github.mayblock.easylib.api.bukkit.menu.event.ClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.SlotClickListener
import com.github.mayblock.easylib.api.bukkit.menu.event.SlotUpdateListener
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.dsl.SlotEventCollectorScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler

class SlotEventCollector<C : ClickEvent, U : UpdateEvent> internal constructor() : SlotEventCollectorScope<C, U> {

    override val clickListeners = mutableListOf<SlotClickListener<C>>()
    override val updateListeners = mutableListOf<SlotUpdateListener<U>>()

    override fun onClick(block: C.() -> Unit) {
        clickListeners.add(block)
    }

    override fun onUpdate(trigger: TaskScheduler.Trigger, block: U.() -> Unit) {
        updateListeners.add(object : SlotUpdateListener<U> {
            override val trigger: TaskScheduler.Trigger = trigger
            override fun onUpdate(event: U) {
                event.block()
            }
        })
    }
}