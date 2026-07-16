package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.BukkitMenu
import com.github.mayblock.easylib.impl.bukkit.util.item
import org.bukkit.Material

/**
 * 菜单槽更新调度：同一槽位上 trigger 相等（值语义）的 [UpdateRule] 合并为一个任务，
 * 按 priority 升序串行执行；不同 trigger 各自排程。只依赖 [BukkitMenu] 公共契约
 * （读写走 getItem/setItem），与具体 UI 类型解耦。
 *
 * **按需运行语义**：仅在有观察者期间运行——由协调者在首个观察者出现时 [start]、
 * 最后一个观察者离开时 [stop]（与 overlay 侧同语义）。
 */
internal class SlotUpdateLoop(
    private val taskScheduler: TaskScheduler,
    private val specs: Map<Int, SlotSpec>,
    private val menu: BukkitMenu,
) {

    private val taskIds = mutableListOf<Int>()

    /** 幂等：已在运行时重复调用直接返回，避免按需启停下重复调度同一批任务。 */
    fun start() {
        if (taskIds.isNotEmpty()) return
        specs.forEach { (index, spec) ->
            spec.updateRules.groupBy { it.trigger }.forEach { (ruleTrigger, rules) ->
                // 与事件总线同约定：priority 小值先执行。同 trigger 规则共享同一事件对象
                // 串行执行（后序规则可见前序修改），块全部结束后统一写入容器一次。
                val ordered = rules.sortedBy { it.priority }
                taskIds += taskScheduler.scheduleTask(ruleTrigger) { // 操作物品需要主线程（默认主线程）
                    // 读容器当前物品（getItem 已返回拷贝，事务快照语义与原实现一致）
                    val current = menu.getItem(index) ?: item(Material.AIR)
                    val event = SlotUpdateEvent(menu, index, current.clone())
                    ordered.forEach { rule -> rule.block(event) }
                    if (event.item != current) menu.setItem(index, event.item)
                }
            }
        }
    }

    fun stop() {
        taskIds.forEach(taskScheduler::cancelTask)
        taskIds.clear()
    }
}
