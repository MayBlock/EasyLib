package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlaySlotEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 覆盖槽的不可变声明：初始物品 + 点击/交互处理器 + 更新规则。零运行态；无 movable/placeable。
 * 由 [OverlaySlotBuilder] 产出，运行期对应物是 [LiveSlot]。
 */
internal class OverlaySlotSpec(
    val item: ItemStack,
    val handlers: List<OverlayHandler>,
    val updateRules: List<OverlayUpdateRule>,
)

/** 点击/交互处理器，按事件类型（[OverlayClickEvent]/[OverlayInteractEvent]）标注，注册时按 `type.isInstance` 过滤。 */
internal class OverlayHandler(
    val priority: Priority,
    val type: Class<out OverlaySlotEvent>,
    val block: OverlaySlotEvent.() -> Unit,
)

internal class OverlayUpdateRule(
    val trigger: TaskScheduler.Trigger,
    val block: OverlayUpdateEvent.() -> Unit,
)
