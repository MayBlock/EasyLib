package com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl

import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayDsl
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 覆盖层单槽 DSL：仅展示 + 交互，**无取出/放入的转移语义，无 take/place**。
 * 玩家操作（窗口内点击 / 手持左右键交互）→ [onAction]（[OverlaySlotActionEvent] 密封层级）；
 * 定时刷新 → [onUpdate]（[OverlayUpdateScope]，主线程，按观察者各算一次）。
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
     * 显示更新规则（纯视觉，契约见 [OverlayUpdateScope]）：按 [trigger] 周期对每个观察者各触发一次，
     * 对 `item` 的修改只影响该玩家看到的样子，不改动共享基底。
     * 同一槽位上 [trigger] 相等的规则合并为一个调度任务，按 [priority] 升序（小值先）串行执行——
     * 后序规则可见前序修改；块全部结束后统一提交并重绘一次。回调在主线程执行。
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
 * [OverlaySlotScope.onUpdate] 的事务上下文（非事件、不上总线）——**显示层契约**：
 *
 * [item] 初值 = 该槽共享基底物品的克隆；对它的修改是**纯视觉**的——只影响 [viewer] 看到的样子
 * （经数据包改写呈现），**不改动共享基底**。其他观察者看到的仍是各自规则算出的结果。
 *
 * 每次触发都从基底重算（`item.amount += 1` 不会跨周期累积，需自存状态）。
 * 同槽**同 trigger** 的多规则合并串行（priority 升序，后者可见前者修改）；同槽**不同 trigger**
 * 的规则组各自从基底全量重算、后触发者整体覆盖——一个槽的显示规则应共用一个 trigger。
 *
 * 需要**全体生效**的真实变更请显式调用 `overlay.setItem`。刻意不暴露 overlay：更新本槽的显示
 * 只有 [item] 一条通道，避免出现与提交协议冲突的第二种写法。
 *
 * 回调在**主线程**执行，块内可安全读写 [viewer] 的 Bukkit 状态。
 */
@PlayerOverlayDsl
interface OverlayUpdateScope {
    /** 本条规则所属的槽位下标。 */
    val index: Int

    /** 本次计算面向的观察者：规则按 trigger 周期**对每个观察者各触发一次**。 */
    val viewer: Player

    /** 该槽面向 [viewer] 的下一帧物品：以基底物品的副本为初值，可原地改或整体替换。 */
    var item: ItemStack
}
