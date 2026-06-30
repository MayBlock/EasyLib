package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 槽的「不可变声明」：用户通过 DSL 声明了什么（初始物品 + 点击处理器 + 更新规则），
 * 零运行态。由 [SlotBuilder] 产出，运行期对应物是 [LiveSlot]。
 */
internal class SlotSpec(
    val item: ItemStack,
    val clickHandlers: List<ClickHandler>,
    val updateRules: List<UpdateRule>,
)

internal class ClickHandler(
    val priority: Priority,
    val type: Class<out SlotClickEvent>,
    val block: SlotClickEvent.() -> Unit,
)

internal class UpdateRule(
    val trigger: TaskScheduler.Trigger,
    val block: SlotUpdateEvent.() -> Unit,
)
