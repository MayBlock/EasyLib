package com.github.mayblock.easylib.api.bukkit.menu.slot.event

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotDsl
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 槽位事件根接口。
 *
 * 标记 [SlotDsl]：槽位事件是 SlotScope DSL 各回调 lambda 的接收者。与 SlotScope 同标记后，
 * 回调体内对外层 builder 成员的隐式访问被禁止——`item(Material.X)` 在回调体内
 * 解析到顶层物品工厂，而非 SlotScope 的槽位物品声明函数（两者同名，成员本会遮蔽顶层）。
 */
@SlotDsl
interface SlotEvent: MenuEvent {
    val index: Int
}

open class SlotClickEvent(
    final override val menu: Menu,
    final override val index: Int,
    val player: Player
): SlotEvent

/**
 * 槽位显示更新事件（**显示层契约**，spec 2026-07-21）：
 *
 * [displayItem] 初值 = 容器真实物品的克隆；对它的修改是**纯视觉**的——只影响 [player]
 * 看到的样子（经数据包改写呈现），**不改动真实容器物品**。取出放行时玩家拿到的是真实物品；
 * 放入放行后下一轮以新的真实物品为基底重新计算。
 *
 * 每次触发都从真实基底重算（`displayItem.amount += 1` 不会跨周期累积，需自存状态）。
 * 同槽**同 trigger** 的多规则合并串行（priority 升序，后者可见前者修改）；同槽**不同 trigger**
 * 的规则组各自从真实基底全量重算、后触发者整体覆盖——一个槽的显示规则应共用一个 trigger。
 * 需要真实变更请显式调用 `menu.setItem`。
 *
 * 注意：伪造 `amount` 建议只用于点击即拒的纯展示槽——允许搬运的槽上，客户端会按可见数量
 * 做本地预测（shift/双击聚堆等），与服务器按真实数量的纠正产生可收敛的视觉抖动。
 */
open class SlotUpdateEvent(
    final override val menu: Menu,
    final override val index: Int,
    val player: Player,
    var displayItem: ItemStack
): SlotEvent