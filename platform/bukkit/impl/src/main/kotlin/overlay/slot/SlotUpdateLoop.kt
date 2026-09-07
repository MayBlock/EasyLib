package com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot

import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.base.api.scheduler.scheduleTask
import com.github.mayblock.easylib.platform.bukkit.api.overlay.slot.dsl.OverlayUpdateScope
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 覆盖槽显示更新调度：对每个观察者按 (viewer, slot) 计算显示物品；
 * **不写回共享基底**。同一槽位上 trigger 相等（值语义）的 [OverlayUpdateRule] 合并为一个任务、
 * 按 priority 升序串行；不同 trigger 各自排程、各自从基底重算，后触发者整体覆盖。
 *
 * 与覆盖层类型解耦——只通过 [repaint] 回调把变更同步给单个观察者。overlay 有「windowId 恒 0 +
 * 入站取消点击」的特权可定向补发，故**不需要**菜单侧的 dirty/flush 合并机制，提交即重绘。
 *
 * 回调通过覆盖层的串行执行器运行；显示提交由持有者校验生命周期和基底版本。
 *
 * **按需运行语义**：仅在有观察者期间运行——由持有者（[com.github.mayblock.easylib.platform.bukkit.impl.overlay.PlayerOverlayImpl]）
 * 在首个观察者出现时 [start]、最后一个观察者离开时 [stop]。[TaskScheduler.Trigger.Once] 类的规则
 * 因此会在每次「从无人到有人」的激活时重新执行一次；这是按需语义的自然结果，而非 bug。
 */
internal class SlotUpdateLoop(
    private val map: SlotMap,
    private val scheduler: TaskScheduler,
    private val callbackExecutor: TaskExecutor,
    private val viewers: () -> List<Player>,
    private val updateDisplay: (Player, Int, (ItemStack) -> ItemStack) -> Boolean,
    private val repaint: (player: Player, index: Int) -> Unit,
) {

    private data class GroupKey(val index: Int, val trigger: TaskScheduler.Trigger)

    /** [OverlayUpdateScope] 的运行期载体：纯数据、不暴露 overlay。 */
    private class UpdateScope(
        override val index: Int,
        override val viewer: Player,
        override var displayItem: ItemStack,
    ) : OverlayUpdateScope

    /** 声明序稳定（groupBy 保序）：种子/重算按此序执行，last-wins 与运行期一致。 */
    private val groupsBySlot: Map<Int, List<Pair<TaskScheduler.Trigger, List<OverlayUpdateRule>>>> =
        buildMap {
            map.forEachUpdatable { index, slot ->
                put(
                    index,
                    slot.updateRules.groupBy { it.trigger }
                        .map { (trigger, rules) -> trigger to rules.sortedBy { it.priority } },
                )
            }
        }

    private val taskIds = mutableListOf<Int>()
    private val fired = mutableSetOf<GroupKey>()
    private val lock = Any()
    private var activation: Any? = null
    private var closed = false

    /** 幂等：已在运行时重复调用直接返回。每次激活重置 Delay 触发标记。 */
    fun start() {
        val current = synchronized(lock) {
            if (closed || activation != null) return
            Any().also { activation = it }
        }
        groupsBySlot.forEach { (index, groups) ->
            groups.forEach { (trigger, ordered) ->
                val id = scheduler.scheduleTask(trigger, callbackExecutor) {
                    synchronized(lock) {
                        if (activation !== current) return@scheduleTask
                        fired += GroupKey(index, trigger)
                    }
                    viewers().forEach { player ->
                        if (compute(index, ordered, player)) repaint(player, index)
                    }
                }
                val cancelled = synchronized(lock) {
                    if (activation === current) { taskIds += id; false } else true
                }
                if (cancelled) scheduler.cancelTask(id)
            }
        }
    }

    fun stop() {
        val ids = synchronized(lock) {
            activation = null
            fired.clear()
            taskIds.toList().also { taskIds.clear() }
        }
        ids.forEach(scheduler::cancelTask)
    }

    fun close() {
        synchronized(lock) { closed = true }
        stop()
    }

    /**
     * 开窗种子：Once/Interval 组必跑；Delay 组仅当本激活周期已触发过（迟到观察者补齐已生效显示，
     * 未到期的尊重延迟语义）。**不重绘**——首帧由 `transport.paintAll` 承载。
     */
    fun seed(player: Player) {
        groupsBySlot.forEach { (index, groups) ->
            groups.forEach { (trigger, ordered) ->
                if (shouldRun(index, trigger)) compute(index, ordered, player)
            }
        }
    }

    /**
     * 基底变更后（`setItem`）重算该槽全 viewer 的显示条目。**不重绘**——发包权归调用方：
     * 基底变了就必须重绘，而本方法的返回值无法表达这一点（规则不改物品时提交返回 false，
     * 但客户端仍持有旧基底）。详见 `PlayerOverlayImpl.setItem`。
     */
    fun recomputeSlot(index: Int) {
        val groups = groupsBySlot[index] ?: return
        viewers().forEach { player ->
            groups.forEach { (trigger, ordered) ->
                if (shouldRun(index, trigger)) compute(index, ordered, player)
            }
        }
    }

    private fun shouldRun(index: Int, trigger: TaskScheduler.Trigger): Boolean =
        trigger !is TaskScheduler.Trigger.Delay || synchronized(lock) { GroupKey(index, trigger) in fired }

    /** 跑一组规则并提交；返回该 viewer 对该槽的可见内容是否变化。 */
    private fun compute(index: Int, ordered: List<OverlayUpdateRule>, player: Player): Boolean =
        updateDisplay(player, index) { base ->
            val scope = UpdateScope(index, player, base)
            ordered.forEach { rule -> rule.block(scope) }
            scope.displayItem
        }
}
