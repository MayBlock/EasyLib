package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 实现 api 的 [SlotScope]，把用户声明收集成不可变的 [SlotSpec]。纯声明、无运行态、无总线。
 *
 * 点击处理器以 [clickType] 标注事件类型——它会被注册到菜单总线并按 `type.isInstance` 过滤，
 * 因此把 `C.()->Unit` 当作 `SlotClickEvent.()->Unit` 存储是安全的。
 */
internal class SlotBuilder<C : SlotClickEvent>(
    private val clickType: Class<C>,
) : SlotScope<C> {

    private val clicks = mutableListOf<ClickHandler>()
    private val updates = mutableListOf<UpdateRule>()

    override fun onClick(priority: Priority, block: C.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        clicks += ClickHandler(priority, clickType, block as SlotClickEvent.() -> Unit)
    }

    override fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority, block: SlotUpdateEvent.() -> Unit) {
        updates += UpdateRule(trigger, block)
    }

    fun build(item: ItemStack): SlotSpec = SlotSpec(item, clicks.toList(), updates.toList())
}
