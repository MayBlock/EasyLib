package com.github.mayblock.easylib.impl.bukkit.overlay.builder

import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.OverlaySlotScope
import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.OverlayUpdateScope
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlayHandler
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlaySlotSpec
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlayUpdateRule
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * 实现 api 的 [OverlaySlotScope]，把用户声明收集成不可变的 [com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlaySlotSpec]。纯声明、无运行态、无总线。
 *
 * 处理器以事件类型标注——会注册到覆盖层总线并按 `type.isInstance` 过滤，
 * 因此把 `OverlaySlotActionEvent.()->Unit` 当作 `OverlaySlotEvent.()->Unit` 存储是安全的。
 */
internal class OverlaySlotBuilder : OverlaySlotScope {

    private var item: ItemStack? = null

    private val handlers = mutableListOf<OverlayHandler>()
    private val updates = mutableListOf<OverlayUpdateRule>()

    override fun item(item: ItemStack) {
        this.item = item.clone()
    }

    override fun <T : OverlaySlotActionEvent> onAction(type: Class<out T>, priority: Priority, block: T.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        OverlayHandler(
            priority,
            type,
            block as OverlaySlotEvent.() -> Unit
        ).also(handlers::add)
    }

    override fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority, block: OverlayUpdateScope.() -> Unit) {
        OverlayUpdateRule(trigger, priority, block).also(updates::add)
    }

    fun build(): OverlaySlotSpec =
        OverlaySlotSpec(item ?: ItemStack(Material.AIR), handlers.toList(), updates.toList())
}
