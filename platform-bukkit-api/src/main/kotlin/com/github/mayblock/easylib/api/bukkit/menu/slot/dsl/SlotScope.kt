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
     * 物品被从本槽位取出（见 [SlotTakeEvent] 的回调职责契约）。
     * v1 仅箱子菜单会派发；玩家背包菜单声明后不会触发。
     */
    fun onTake(priority: Priority = Priority.DEFAULT, block: SlotTakeEvent.() -> Unit)

    /**
     * 玩家物品被放入本槽位（见 [SlotPlaceEvent] 的回调职责契约）。
     * v1 仅箱子菜单会派发；玩家背包菜单声明后不会触发。
     */
    fun onPlace(priority: Priority = Priority.DEFAULT, block: SlotPlaceEvent.() -> Unit)
}
