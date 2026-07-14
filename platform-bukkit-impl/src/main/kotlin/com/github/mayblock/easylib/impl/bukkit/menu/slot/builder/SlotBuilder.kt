package com.github.mayblock.easylib.impl.bukkit.menu.slot.builder

import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotUpdateEvent
import com.github.mayblock.easylib.api.event.Event
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.menu.slot.ClickHandler
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.menu.slot.UpdateRule
import org.bukkit.inventory.ItemStack
import kotlin.collections.plusAssign

/**
 * 实现 api 的 [com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope]，把用户声明收集成不可变的 [com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec]。纯声明、无运行态、无总线。
 *
 * 点击处理器以 [clickType] 标注事件类型——它会被注册到菜单总线并按 `type.isInstance` 过滤，
 * 因此把 `C.()->Unit` 当作 `SlotClickEvent.()->Unit` 存储是安全的。
 */
internal class SlotBuilder<C : SlotClickEvent>(
    private val clickType: Class<out C>,
) : SlotScope<C> {

    private val clicks = mutableListOf<ClickHandler>()
    private val updates = mutableListOf<UpdateRule>()

    override fun onClick(priority: Priority, block: C.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        ClickHandler(priority, clickType, block as SlotClickEvent.() -> Unit).also(clicks::add)
    }

    override fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority, block: SlotUpdateEvent.() -> Unit) {
        UpdateRule(trigger, priority, block).also(updates::add)
    }

    override fun onTake(priority: Priority, block: SlotTakeEvent.() -> Unit) {
        ClickHandler(priority, SlotTakeEvent::class.java, cancellingOnException(block)).also(clicks::add)
    }

    override fun onPlace(priority: Priority, block: SlotPlaceEvent.() -> Unit) {
        ClickHandler(priority, SlotPlaceEvent::class.java, cancellingOnException(block)).also(clicks::add)
    }

    fun build(item: ItemStack, movable: Boolean = false, placeable: Boolean = false): SlotSpec =
        SlotSpec(item, clicks.toList(), updates.toList(), movable, placeable)

    /**
     * 事件总线会吞掉监听器异常（记日志后继续）。take/place 回调是放行门：
     * 半途异常必须视为取消，否则异常被吞后原生/引擎仍会完成物品移动，等于「未把关即放行」。
     * 这里先置取消再重新抛出，日志仍由总线负责。
     */
    private fun <E> cancellingOnException(block: E.() -> Unit): SlotClickEvent.() -> Unit
        where E : SlotClickEvent, E : Event.Cancellable = {
        @Suppress("UNCHECKED_CAST")
        val event = this as E
        try {
            block(event)
        } catch (e: Exception) {
            event.isCancelled = true
            throw e
        }
    }
}