package com.github.mayblock.easylib.api.bukkit.menu.slot.dsl

import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority

@DslMarker
annotation class SlotDsl

@SlotDsl
interface SlotScope<out C : SlotClickEvent> {
    fun onClick(priority: Priority = Priority.DEFAULT, block: C.() -> Unit)
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: SlotUpdateEvent.() -> Unit)

    /**
     * 物品被从本槽位取出时的把关点（回调只把关/观察，不搬运物品，契约见 [SlotTakeEvent]）。
     * 目前仅箱子菜单使用本 DSL 并派发该事件。
     */
    fun onTake(priority: Priority = Priority.DEFAULT, block: SlotTakeEvent.() -> Unit)

    /**
     * 玩家物品被放入本槽位时的把关点（回调只把关/观察，不搬运物品，契约见 [SlotPlaceEvent]）。
     * 目前仅箱子菜单使用本 DSL 并派发该事件。
     */
    fun onPlace(priority: Priority = Priority.DEFAULT, block: SlotPlaceEvent.() -> Unit)
}
