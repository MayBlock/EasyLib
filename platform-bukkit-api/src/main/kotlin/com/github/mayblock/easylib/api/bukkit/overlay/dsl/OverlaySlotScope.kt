package com.github.mayblock.easylib.api.bukkit.overlay.dsl

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 覆盖层单槽 DSL：仅展示 + 交互，**无 movable/placeable、无 take/place**。
 * 背包窗口内点击 → [onClick]（[OverlayClickEvent]）；手持挥动/使用 → [onInteract]（[OverlayInteractEvent]）；
 * 定时刷新 → [onUpdate]（[OverlayUpdateScope]，仍异步）。
 */
@PlayerOverlayDsl
interface OverlaySlotScope {
    fun onClick(priority: Priority = Priority.DEFAULT, block: OverlayClickEvent.() -> Unit)
    fun onInteract(priority: Priority = Priority.DEFAULT, block: OverlayInteractEvent.() -> Unit)

    /**
     * 定时更新规则。同一槽位上 [trigger] 相等的规则合并为一个调度任务，按 [priority]
     * 升序（小值先）串行执行——后序规则可见前序修改；块全部结束后统一提交并重绘一次。
     */
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: OverlayUpdateScope.() -> Unit)
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
