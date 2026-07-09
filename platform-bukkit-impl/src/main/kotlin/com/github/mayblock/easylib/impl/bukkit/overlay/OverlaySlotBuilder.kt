package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlaySlotEvent
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.OverlaySlotScope
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.OverlayUpdateScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 实现 api 的 [OverlaySlotScope]，把用户声明收集成不可变的 [OverlaySlotSpec]。纯声明、无运行态、无总线。
 *
 * 处理器以事件类型标注——会注册到覆盖层总线并按 `type.isInstance` 过滤，
 * 因此把 `OverlayClickEvent.()->Unit` 当作 `OverlaySlotEvent.()->Unit` 存储是安全的。
 */
internal class OverlaySlotBuilder : OverlaySlotScope {

    private val handlers = mutableListOf<OverlayHandler>()
    private val updates = mutableListOf<OverlayUpdateRule>()

    override fun onClick(priority: Priority, block: OverlayClickEvent.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        handlers += OverlayHandler(priority, OverlayClickEvent::class.java, block as OverlaySlotEvent.() -> Unit)
    }

    override fun onInteract(priority: Priority, block: OverlayInteractEvent.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        handlers += OverlayHandler(priority, OverlayInteractEvent::class.java, block as OverlaySlotEvent.() -> Unit)
    }

    override fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority, block: OverlayUpdateScope.() -> Unit) {
        updates += OverlayUpdateRule(trigger, block)
    }

    fun build(item: ItemStack): OverlaySlotSpec =
        OverlaySlotSpec(item, handlers.toList(), updates.toList())
}
