package com.github.mayblock.easylib.platform.bukkit.impl.menu.slot

import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.dsl.SlotUpdateScope
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.scheduleTask
import com.github.mayblock.easylib.platform.bukkit.impl.menu.BukkitMenu
import com.github.mayblock.easylib.platform.bukkit.impl.util.SlotDisplayMap
import com.github.mayblock.easylib.platform.bukkit.impl.util.stack
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*

/**
 * 菜单槽显示更新调度（spec §7/§8）：对每个观察者按 (viewer, slot) 计算假显示并提交
 * [SlotDisplayMap]；**不写回真实容器**。同一槽位上 trigger 相等（值语义）的 [UpdateRule]
 * 合并为一个任务、按 priority 升序串行；不同 trigger 各自排程、各自从真实基底重算，
 * 后触发者整体覆盖（种子按声明序，last-wins 一致）。
 *
 * 脏 viewer 经每 tick 至多一次的合并刷新任务调 [repaint]（生产 = `Player.updateInventory()`，
 * 服务器按正确 stateId 自发全量包、经出站改写层变假——方案 A 的刷新通道）。
 *
 * **按需运行语义**：首个观察者 [start]、最后一个离开 [stop]（与 overlay 侧同语义）。
 * Delay 组"本周期已触发"等运行态在本类（[SlotSpec] 保持零运行态契约）。
 */
internal class SlotUpdateLoop(
    private val taskScheduler: TaskScheduler,
    private val syncExecutionContext: BukkitExecutionContext.Sync,
    specs: Map<Int, SlotSpec>,
    private val menu: BukkitMenu,
    private val viewers: () -> List<Player>,
    private val display: SlotDisplayMap,
    private val repaint: (Player) -> Unit,
) {

    private data class GroupKey(val index: Int, val trigger: TaskScheduler.Trigger)

    /** [SlotUpdateScope] 的运行期载体：纯数据、不暴露 menu。 */
    private class UpdateScope(
        override val index: Int,
        override val viewer: Player,
        override var displayItem: ItemStack,
    ) : SlotUpdateScope

    /** 声明序稳定（groupBy 保序）：种子/重算按此序执行，last-wins 与运行期一致。 */
    private val groupsBySlot: Map<Int, List<Pair<TaskScheduler.Trigger, List<UpdateRule>>>> =
        specs.filterValues { it.updateRules.isNotEmpty() }.mapValues { (_, spec) ->
            spec.updateRules.groupBy { it.trigger }
                .map { (trigger, rules) -> trigger to rules.sortedBy { it.priority } }
        }

    private val taskIds = mutableListOf<Int>()
    private val fired = mutableSetOf<GroupKey>()      // 本激活周期已触发的组（主线程）
    private val dirty = mutableSetOf<UUID>()          // 待重绘 viewer（主线程）
    private var flushScheduled = false

    /** 幂等：已在运行时重复调用直接返回。每次激活重置 Delay 触发标记。 */
    fun start() {
        if (taskIds.isNotEmpty()) return
        fired.clear()
        groupsBySlot.forEach { (index, groups) ->
            groups.forEach { (trigger, ordered) ->
                taskIds += taskScheduler.scheduleTask(trigger, syncExecutionContext) {
                    fired += GroupKey(index, trigger)
                    viewers().forEach { player ->
                        if (compute(index, ordered, player)) markDirty(player.uniqueId)
                    }
                }
            }
        }
    }

    fun stop() {
        taskIds.forEach(taskScheduler::cancelTask)
        taskIds.clear()
        fired.clear()
        dirty.clear()
        display.clear()
    }

    /**
     * 开窗种子（spec §8）：Once/Interval 组必跑；Delay 组仅当本激活周期已触发过
     * （迟到观察者补齐已生效显示，未到期的尊重延迟语义）。**不标脏**——首帧由
     * 开窗自然发包经改写层呈现，无需额外重绘。
     */
    fun seed(player: Player) {
        groupsBySlot.forEach { (index, groups) ->
            groups.forEach { (trigger, ordered) ->
                if (shouldRun(index, trigger)) compute(index, ordered, player)
            }
        }
    }

    /** 真实变更·已知新值路径（setItem / shift 写入后）：同步重算该槽全 viewer 并标脏。 */
    fun recomputeSlot(index: Int) {
        val groups = groupsBySlot[index] ?: return
        viewers().forEach { player ->
            var changed = false
            groups.forEach { (trigger, ordered) ->
                if (shouldRun(index, trigger) && compute(index, ordered, player)) changed = true
            }
            if (changed) markDirty(player.uniqueId)
        }
    }

    /**
     * 真实变更·原生点击放行路径（新值要等 NMS 应用）：立即清条目（改写层透传真实，
     * 与服务器即将广播的内容一致），下一 tick 重算恢复美化（≤1 tick 素颜间隙，spec §8/§10）。
     */
    fun invalidateSlot(index: Int) {
        if (index !in groupsBySlot) return
        display.invalidate(index)
        taskScheduler.scheduleTask(TaskScheduler.Trigger.Once, syncExecutionContext) { recomputeSlot(index) }
    }

    private fun shouldRun(index: Int, trigger: TaskScheduler.Trigger): Boolean =
        trigger !is TaskScheduler.Trigger.Delay || GroupKey(index, trigger) in fired

    /** 跑一组规则并提交；返回该 viewer 视图是否变化。 */
    private fun compute(index: Int, ordered: List<UpdateRule>, player: Player): Boolean {
        val base = menu.getItem(index) ?: stack(Material.AIR)
        val scope = UpdateScope(index, player, base.clone())
        ordered.forEach { rule -> rule.block(scope) }
        return display.commit(player.uniqueId, index, base, scope.displayItem)
    }

    private fun markDirty(viewerId: UUID) {
        dirty += viewerId
        if (flushScheduled) return
        flushScheduled = true
        taskScheduler.scheduleTask(TaskScheduler.Trigger.Once, syncExecutionContext) { flush() } // 同 tick 多槽/多组合并
    }

    private fun flush() {
        flushScheduled = false
        if (dirty.isEmpty()) return
        val ids = dirty.toSet()
        dirty.clear()
        viewers().forEach { if (it.uniqueId in ids) repaint(it) }
    }
}
