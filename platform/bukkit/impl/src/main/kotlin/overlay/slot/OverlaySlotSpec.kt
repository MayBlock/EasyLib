package com.github.mayblock.easylib.base.impl.bukkit.overlay.slot

import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotEvent
import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.OverlayUpdateScope
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 覆盖槽的不可变声明：初始物品 + 点击/交互处理器 + 更新规则。零运行态；无取出/放入转移语义。
 * 由 [com.github.mayblock.easylib.base.impl.bukkit.overlay.builder.OverlaySlotBuilder] 产出，运行期对应物是 [com.github.mayblock.easylib.base.impl.bukkit.overlay.slot.LiveSlot]。
 */
internal class OverlaySlotSpec(
    val item: ItemStack,
    val handlers: List<OverlayHandler>,
    val updateRules: List<OverlayUpdateRule>,
)

/** 玩家操作处理器，按事件类型（[com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent]）标注，注册时按 `type.isInstance` 过滤。 */
internal class OverlayHandler(
    val priority: Priority,
    val type: Class<out OverlaySlotEvent>,
    val block: OverlaySlotEvent.() -> Unit,
)

internal class OverlayUpdateRule(
    val trigger: TaskScheduler.Trigger,
    val priority: Priority,
    val block: OverlayUpdateScope.() -> Unit,
)
