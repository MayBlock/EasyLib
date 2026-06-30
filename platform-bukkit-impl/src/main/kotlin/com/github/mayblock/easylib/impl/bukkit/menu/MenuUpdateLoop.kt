package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler

/**
 * 槽更新调度：每个 [com.github.mayblock.easylib.impl.bukkit.menu.slot.UpdateRule] 各按自己的 trigger 排程。
 * 与菜单类型解耦——只通过 [repaint] 回调把变更同步给观察者。
 */
internal class MenuUpdateLoop(
    private val menu: Menu,
    private val grid: SlotGrid,
    private val scheduler: TaskScheduler,
    private val repaint: (index: Int) -> Unit,
) {
    private val taskIds = mutableListOf<Int>()

    fun start() {
        grid.forEachUpdatable { index, slot ->
            slot.updateRules.forEach { rule ->
                taskIds += scheduler.scheduleTask {
                    trigger = rule.trigger
                    isAsync = true
                    onTick = {
                        val event = SlotUpdateEvent(menu, index, slot.item.clone()).apply(rule.block)
                        if (event.item != slot.item) {
                            slot.item = event.item
                            repaint(index)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        taskIds.forEach(scheduler::cancelTask)
        taskIds.clear()
    }
}
