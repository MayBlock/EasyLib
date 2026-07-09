package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.event.Event
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
    private val clickType: Class<out C>,
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

    override fun onTake(priority: Priority, block: SlotTakeEvent.() -> Unit) {
        clicks += ClickHandler(priority, SlotTakeEvent::class.java, cancellingOnException(block))
    }

    override fun onPlace(priority: Priority, block: SlotPlaceEvent.() -> Unit) {
        clicks += ClickHandler(priority, SlotPlaceEvent::class.java, cancellingOnException(block))
    }

    fun build(item: ItemStack, movable: Boolean = false, placeable: Boolean = false): SlotSpec =
        SlotSpec(item, clicks.toList(), updates.toList(), movable, placeable)

    /**
     * 事件总线会吞掉监听器异常（记日志后继续）。take/place 回调承担「真实物品给予/扣除」职责，
     * 半途异常必须视为取消，否则会出现「虚拟层已提交、真实操作未完成」的不一致。
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
