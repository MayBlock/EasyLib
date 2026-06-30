package com.github.mayblock.easylib.api.bukkit.menu.slot.dsl

import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority

@DslMarker
annotation class SlotDsl

@SlotDsl
interface SlotScope<out C : SlotClickEvent> {
    fun onClick(priority: Priority = Priority.DEFAULT, block: C.() -> Unit)
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: SlotUpdateEvent.() -> Unit)
}
