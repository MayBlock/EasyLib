package com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl

import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayDsl
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 覆盖层单槽 DSL：仅展示 + 交互，**无取出/放入的转移语义，无 take/place**。
 * 玩家操作（窗口内点击 / 手持左右键交互）→ [onAction]（[OverlaySlotActionEvent] 密封层级）；
 * 定时刷新 → [onUpdate]（[OverlayUpdateScope]，仍异步）。
 */
@PlayerOverlayDsl
interface OverlaySlotScope {
    /**
     * 玩家操作的统一入口。块内用 `when (this)` 区分来源（密封类，可穷尽）：
     * [OverlaySlotActionEvent.Click] = 背包窗口内点击（带 Bukkit ClickType）；
     * [OverlaySlotActionEvent.Interact] = 手持该槽物品的左/右键交互。
     *
     * 操作发生在数据包处理线程，回调经调度器转发到**主线程**执行。
     */
    fun <T: OverlaySlotActionEvent> onAction(type: Class<out T>, priority: Priority = Priority.DEFAULT, block: T.() -> Unit)

    /**
     * 定时更新规则。同一槽位上 [trigger] 相等的规则合并为一个调度任务，按 [priority]
     * 升序（小值先）串行执行——后序规则可见前序修改；块全部结束后统一提交并重绘一次。
     */
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: OverlayUpdateScope.() -> Unit)
}

fun OverlaySlotScope.onAction(
    priority: Priority = Priority.DEFAULT,
    block: OverlaySlotActionEvent.() -> Unit
) {
    onAction(OverlaySlotActionEvent::class.java, priority, block)
}

@JvmName("onActionWithType")
inline fun <reified T: OverlaySlotActionEvent> OverlaySlotScope.onAction(
    priority: Priority = Priority.DEFAULT,
    noinline block: T.() -> Unit
) {
    onAction(T::class.java, priority, block)
}

/**
 * [OverlaySlotScope.onUpdate] 的事务上下文（非事件、不上总线）：改写 [item]，块结束后
 * 引擎提交并重绘该槽——仅当物品确实变化，且本 tick 内没有其他写入（若有，提案作废，
 * 绝不回滚他人写入）。刻意不暴露 overlay：更新本槽只有 [item] 一条通道，
 * 避免出现与提交协议冲突的第二种写法。
 */
@PlayerOverlayDsl
interface OverlayUpdateScope {
    /** 本条规则所属的槽位下标。 */
    val index: Int

    /** 该槽的下一帧物品：以当前物品的副本为初值，可原地改或整体替换。 */
    var item: ItemStack
}
