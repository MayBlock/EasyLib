package com.github.mayblock.easylib.api.bukkit.menu.slot.dsl

import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

@DslMarker
internal annotation class SlotDsl

@SlotDsl
interface SlotScope<out C : SlotClickEvent> {

    fun item(item: ItemStack)

    fun onClick(priority: Priority = Priority.DEFAULT, block: C.() -> Unit)

    /**
     * 显示更新规则（纯视觉，契约见 [SlotUpdateScope]）：按 [trigger] 周期对每个观察者各触发一次，
     * 对 `displayItem` 的修改只影响该玩家看到的样子，不写回真实容器。
     * 同一槽位上 [trigger] 相等的规则合并为一个调度任务，按 [priority] 升序（小值先）串行执行。
     */
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: SlotUpdateScope.() -> Unit)

    /**
     * 物品被从本槽位取出时的把关点（回调只把关/观察，不搬运物品，契约见 [SlotTakeEvent]）。
     * 事件默认取消：不注册本回调 ⇒ 永不放行；注册后须显式 `isCancelled = false` 才放行本次取出，
     * 可按条件动态决定。目前仅箱子菜单使用本 DSL 并派发该事件。
     */
    fun onTake(priority: Priority = Priority.DEFAULT, block: SlotTakeEvent.() -> Unit)

    /**
     * 玩家物品被放入本槽位时的把关点（回调只把关/观察，不搬运物品，契约见 [SlotPlaceEvent]）。
     * 事件默认取消：不注册本回调 ⇒ 永不放行；注册后须显式 `isCancelled = false` 才放行本次放入，
     * 可按条件动态决定。目前仅箱子菜单使用本 DSL 并派发该事件。
     */
    fun onPlace(priority: Priority = Priority.DEFAULT, block: SlotPlaceEvent.() -> Unit)
}

/**
 * [SlotScope.onUpdate] 的事务上下文（非事件、不上总线）——**显示层契约**（spec 2026-07-21）：
 *
 * [displayItem] 初值 = 容器真实物品的克隆；对它的修改是**纯视觉**的——只影响 [viewer]
 * 看到的样子（经数据包改写呈现），**不改动真实容器物品**。取出放行时玩家拿到的是真实物品；
 * 放入放行后下一轮以新的真实物品为基底重新计算。
 *
 * 每次触发都从真实基底重算（`displayItem.amount += 1` 不会跨周期累积，需自存状态）。
 * 同槽**同 trigger** 的多规则合并串行（priority 升序，后者可见前者修改）；同槽**不同 trigger**
 * 的规则组各自从真实基底全量重算、后触发者整体覆盖——一个槽的显示规则应共用一个 trigger。
 *
 * 需要真实变更请显式调用 `menu.setItem`。刻意不暴露 menu：更新本槽的显示只有 [displayItem]
 * 一条通道，避免出现与提交协议冲突的第二种写法。
 *
 * 注意：伪造 `amount` 建议只用于点击即拒的纯展示槽——允许搬运的槽上，客户端会按可见数量
 * 做本地预测（shift/双击聚堆等），与服务器按真实数量的纠正产生可收敛的视觉抖动。
 *
 * 回调在**主线程**执行，块内可安全读写 [viewer] 的 Bukkit 状态。
 */
@SlotDsl
interface SlotUpdateScope {
    /** 本条规则所属的槽位下标。 */
    val index: Int

    /** 本次计算面向的观察者：规则按 trigger 周期**对每个观察者各触发一次**。 */
    val viewer: Player

    /** 该槽面向 [viewer] 的下一帧物品：以真实物品的副本为初值，可原地改或整体替换。 */
    var displayItem: ItemStack
}

inline fun SlotScope<*>.item(item: ItemStack, metadata: ItemMeta.() -> Unit) = item.clone().also {
    it.itemMeta = it.itemMeta?.also(metadata)
}.let(::item)

fun SlotScope<*>.item(type: Material, amount: Int = 1) = this.item(ItemStack(type, amount))
inline fun SlotScope<*>.item(type: Material, amount: Int = 1, metadata: ItemMeta.() -> Unit) {
    ItemStack(type, amount).also {
        it.itemMeta = it.itemMeta?.also(metadata)
    }.let(::item)
}
