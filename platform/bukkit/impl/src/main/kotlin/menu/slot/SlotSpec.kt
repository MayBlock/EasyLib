package com.github.mayblock.easylib.platform.bukkit.impl.menu.slot

import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.util.Priority
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.dsl.SlotUpdateScope
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.SlotClickEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.SlotTakeEvent
import org.bukkit.inventory.ItemStack

/**
 * 槽的「不可变声明」：用户通过 DSL 声明了什么（初始物品 + 处理器 + 更新规则），零运行态。
 * 由 [com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.builder.SlotBuilder] 产出（chest 侧物品直接写入真实 Bukkit 容器）。
 *
 * 槽位不再有可拿取/可放置的静态布尔标志：取出/放入是否放行由 [SlotTakeEvent]/[SlotPlaceEvent] 的
 * 事件契约（默认取消，handler 显式放行）决定，门控只看槽位是否声明（见 [ChestSlotGate]）。
 */
internal class SlotSpec(
    val item: ItemStack,
    val handlers: List<SlotHandler>,
    val updateRules: List<UpdateRule>,
) {
    /** 本槽是否声明了任何 [SlotPlaceEvent] 处理器；构造期算好存字段，供 shift-入菜单候选槽预筛（纯优化）。 */
    val hasPlaceHandlers: Boolean = handlers.any { SlotPlaceEvent::class.java.isAssignableFrom(it.type) }
}

/** 承载 [SlotClickEvent] 层级的全部处理器：onClick/onTake/onPlace 均以此类型收集，按 `type.isInstance` 过滤派发。 */
internal class SlotHandler(
    val priority: Priority,
    val type: Class<out SlotClickEvent>,
    val block: SlotClickEvent.() -> Unit,
)

internal class UpdateRule(
    val trigger: TaskScheduler.Trigger,
    val priority: Priority,
    val block: SlotUpdateScope.() -> Unit,
)
