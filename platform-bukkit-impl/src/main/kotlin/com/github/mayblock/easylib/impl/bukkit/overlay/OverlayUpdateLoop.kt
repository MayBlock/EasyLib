package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayUpdateEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.scheduler.TaskScheduler

/**
 * 覆盖槽更新调度：每个 [OverlayUpdateRule] 各按自己的 trigger 排程。
 * 与覆盖层类型解耦——只通过 [repaint] 回调把变更同步给观察者。overlay 纯发包，**保持异步**。
 */
internal class OverlayUpdateLoop(
    private val overlay: PlayerOverlay,
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
                        val event = OverlayUpdateEvent(overlay, index, slot.item.clone()).apply(rule.block)
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
