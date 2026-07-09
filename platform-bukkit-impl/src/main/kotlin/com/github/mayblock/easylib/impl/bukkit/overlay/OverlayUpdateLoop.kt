package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.dsl.OverlayUpdateScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import org.bukkit.inventory.ItemStack

/**
 * 覆盖槽更新调度：每个 [OverlayUpdateRule] 各按自己的 trigger 排程。
 * 与覆盖层类型解耦——只通过 [repaint] 回调把变更同步给观察者。overlay 纯发包，**保持异步**。
 */
internal class OverlayUpdateLoop(
    private val grid: SlotGrid,
    private val scheduler: TaskScheduler,
    private val repaint: (index: Int) -> Unit,
) {
    private val taskIds = mutableListOf<Int>()

    /** [OverlayUpdateScope] 的运行期载体：纯数据、不暴露 overlay。 */
    private class UpdateScope(
        override val index: Int,
        override var item: ItemStack,
    ) : OverlayUpdateScope

    fun start() {
        grid.forEachUpdatable { index, slot ->
            slot.updateRules.forEach { rule ->
                taskIds += scheduler.scheduleTask {
                    trigger = rule.trigger
                    isAsync = true
                    onTick = {
                        val before = slot.item
                        val scope = UpdateScope(index, before.clone()).apply(rule.block)
                        // 提交提案：仅当块确实改了物品（值比较），且本 tick 无人直接写入本槽
                        // （写时克隆 ⇒ 每次写入都是新对象，=== 即版本戳）。有写入则提案作废，
                        // 绝不用过期提案回滚更新的值。
                        if (scope.item != before && slot.item === before) {
                            slot.item = scope.item
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
