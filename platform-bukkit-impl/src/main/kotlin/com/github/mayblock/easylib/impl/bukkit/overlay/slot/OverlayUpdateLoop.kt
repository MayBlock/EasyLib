package com.github.mayblock.easylib.impl.bukkit.overlay.slot

import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.OverlayUpdateScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotGrid
import org.bukkit.inventory.ItemStack

/**
 * 覆盖槽更新调度：同一槽位上 trigger 相等（值语义）的 [OverlayUpdateRule] 合并为一个任务，
 * 按 priority 升序串行执行；不同 trigger 各自排程。
 * 与覆盖层类型解耦——只通过 [repaint] 回调把变更同步给观察者。overlay 纯发包，**保持异步**。
 *
 * **按需运行语义**：仅在有观察者期间运行——由持有者（[PlayerOverlayImpl]）在首个观察者出现时
 * [start]、最后一个观察者离开时 [stop]。[TaskScheduler.Trigger.Once] 类的规则因此会在每次
 * 「从无人到有人」的激活时重新执行一次；这是按需语义的自然结果，而非 bug。
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

    /** 幂等：已在运行时重复调用直接返回，避免按需启停下重复调度同一批任务。 */
    fun start() {
        if (taskIds.isNotEmpty()) return
        grid.forEachUpdatable { index, slot ->
            slot.updateRules.groupBy { it.trigger }.forEach { (ruleTrigger, rules) ->
                // 与事件总线同约定：priority 小值先执行。同 trigger 规则共享同一事务上下文
                // 串行执行（后序规则可见前序修改），块全部结束后统一提交一次。
                val ordered = rules.sortedBy { it.priority }
                taskIds += scheduler.scheduleTask {
                    trigger = ruleTrigger
                    isAsync = true
                    onTick = {
                        val before = slot.item
                        val scope = UpdateScope(index, before.clone())
                        ordered.forEach { rule -> rule.block(scope) }
                        // 提交提案：仅当规则确实改了物品（值比较），且本 tick 无人直接写入本槽
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
