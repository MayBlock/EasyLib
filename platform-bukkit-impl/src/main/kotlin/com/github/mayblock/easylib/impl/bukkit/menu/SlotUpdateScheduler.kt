package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.event.SlotUpdateListener
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdatableSlot
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable

internal class SlotUpdateScheduler<E : UpdateEvent>(
    private val scheduler: TaskScheduler,
    private val handler: (index: Int, listener: SlotUpdateListener<E>) -> Unit
) {
    private val activeTasks = mutableMapOf<UpdatableSlot<E>, Disposable>()

    fun schedule(index: Int, slot: UpdatableSlot<E>) {
        cancel(slot) // 防止同一个slot被重复执行
        val taskIds = slot.updateListeners.map { listener ->
            scheduler.scheduleTask {
                trigger = listener.trigger
                isAsync = true
                onTick = {
                    handler(index, listener)
                }
            }
        }
        activeTasks[slot] = Disposable {
            taskIds.forEach(scheduler::cancelTask)
        }
    }

    fun cancel(slot: UpdatableSlot<E>) {
        activeTasks.remove(slot)?.also { it.dispose() }
    }

    fun cancelAllTasks() {
        activeTasks.values.forEach { it.dispose() }
        activeTasks.clear()
    }
}