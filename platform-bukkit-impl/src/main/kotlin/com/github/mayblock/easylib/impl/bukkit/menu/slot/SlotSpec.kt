package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 槽的「不可变声明」：用户通过 DSL 声明了什么（初始物品 + 点击处理器 + 更新规则 + 交互能力），
 * 零运行态。由 [SlotBuilder] 产出（chest 侧物品直接写入真实 Bukkit 容器）。
 *
 * @param movable 槽中物品可被玩家拿起（真实给予由 take 回调负责）
 * @param placeable 玩家可把自己背包的物品放入本槽（真实扣除由 place 回调负责）
 */
internal class SlotSpec(
    val item: ItemStack,
    val clickHandlers: List<ClickHandler>,
    val updateRules: List<UpdateRule>,
    val movable: Boolean = false,
    val placeable: Boolean = false,
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
