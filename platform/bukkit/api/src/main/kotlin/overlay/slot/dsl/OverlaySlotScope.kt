package com.github.mayblock.easylib.platform.bukkit.api.overlay.slot.dsl

import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.util.Priority
import com.github.mayblock.easylib.platform.bukkit.api.overlay.dsl.PlayerOverlayDsl
import com.github.mayblock.easylib.platform.bukkit.api.overlay.slot.event.OverlaySlotActionEvent
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * 覆盖层单槽 DSL：仅展示 + 交互，**无取出/放入的转移语义，无 take/place**。
 * 玩家操作（窗口内点击 / 手持左右键交互）→ [onAction]（[OverlaySlotActionEvent] 密封层级）；
 * 定时刷新 → [onUpdate]（[OverlayUpdateScope]，按观察者各算一次）。
 */
@PlayerOverlayDsl
interface OverlaySlotScope {

    fun item(item: ItemStack)

    /**
     * 玩家操作的统一入口。块内用 `when (this)` 区分来源（密封类，可穷尽）：
     * [OverlaySlotActionEvent.Click] = 背包窗口内点击（带 Bukkit ClickType）；
     * [OverlaySlotActionEvent.Interact] = 手持该槽物品的左/右键交互。
     *
     * 数据包线程只负责拦截；回调转发到工厂指定的上下文，默认 Sync，可显式选择 Async。
     * 首帧尚未准备好或会话已隐藏的操作不派发回调。
     */
    fun <T: OverlaySlotActionEvent> onAction(type: Class<out T>, priority: Priority = Priority.DEFAULT, block: T.() -> Unit)

    /**
     * 显示更新规则（纯视觉，契约见 [OverlayUpdateScope]）：按 [trigger] 周期对每个观察者各触发一次；
     * 同槽同 [trigger] 的规则合并为一个任务按 [priority] 升序串行。
     * 首帧、定时更新和 setItem 重算使用同一执行上下文，默认 Sync。详见 `docs/overlay.md`。
     */
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: OverlayUpdateScope.() -> Unit)
}

inline fun OverlaySlotScope.item(item: ItemStack, metadata: ItemMeta.() -> Unit) = item.clone().also {
    it.itemMeta = it.itemMeta?.also(metadata)
}.let(::item)

fun OverlaySlotScope.item(type: Material, amount: Int = 1) = this.item(ItemStack(type, amount))
inline fun OverlaySlotScope.item(type: Material, amount: Int = 1, metadata: ItemMeta.() -> Unit) {
    ItemStack(type, amount).also {
        it.itemMeta = it.itemMeta?.also(metadata)
    }.let(::item)
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
 * [OverlaySlotScope.onUpdate] 的事务上下文（非事件、不上总线）——显示层契约：
 * [displayItem] 初值为共享基底的克隆，修改**纯视觉**、只影响 [viewer] 所见、不改动基底，且每次触发从基底重算；
 * 需要全体生效的真实变更请调用 `PlayerOverlay.setItem`。完整契约见 `docs/overlay.md`。
 * 选择 Async 后，调用方只应读取安全快照，不应在回调中直接访问要求主线程的 Bukkit 状态。
 */
@PlayerOverlayDsl
interface OverlayUpdateScope {
    /** 本条规则所属的槽位下标。 */
    val index: Int

    /** 本次计算面向的观察者：规则按 trigger 周期**对每个观察者各触发一次**。 */
    val viewer: Player

    /** 该槽面向 [viewer] 的下一帧物品：以基底物品的副本为初值，可原地改或整体替换。 */
    var displayItem: ItemStack
}
