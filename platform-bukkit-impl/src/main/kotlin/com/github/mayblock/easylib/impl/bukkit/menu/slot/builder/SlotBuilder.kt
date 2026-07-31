package com.github.mayblock.easylib.impl.bukkit.menu.slot.builder

import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotUpdateScope
import com.github.mayblock.easylib.api.event.Event
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotHandler
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.menu.slot.UpdateRule
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * 实现 api 的 [com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope]，把用户声明收集成不可变的 [com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec]。纯声明、无运行态、无总线。
 *
 * 点击处理器以 [clickType] 标注事件类型——它会被注册到菜单总线并按 `type.isInstance` 过滤，
 * 因此把 `C.()->Unit` 当作 `SlotClickEvent.()->Unit` 存储是安全的。
 */
internal class SlotBuilder<out C : SlotClickEvent>(
    private val clickType: Class<out C>,
) : SlotScope<C> {

    private var item: ItemStack? = null

    private val clicks = mutableListOf<SlotHandler>()
    private val updates = mutableListOf<UpdateRule>()

    override fun item(item: ItemStack) {
        // clone：避免调用方事后改动传入实例穿透进 SlotSpec/真实容器
        //（也隔离分页导航物品这类同一实例多次声明的共享，见 PageableChestMenuBuilder）。
        this.item = item.clone()
    }

    override fun onClick(priority: Priority, block: C.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        SlotHandler(priority, clickType, block as SlotClickEvent.() -> Unit).also(clicks::add)
    }

    override fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority, block: SlotUpdateScope.() -> Unit) {
        UpdateRule(trigger, priority, block).also(updates::add)
    }

    override fun onTake(priority: Priority, block: SlotTakeEvent.() -> Unit) {
        SlotHandler(priority, SlotTakeEvent::class.java, cancellingOnException(block)).also(clicks::add)
    }

    override fun onPlace(priority: Priority, block: SlotPlaceEvent.() -> Unit) {
        SlotHandler(priority, SlotPlaceEvent::class.java, cancellingOnException(block)).also(clicks::add)
    }

    fun build(): SlotSpec =
        SlotSpec(item ?: ItemStack(Material.AIR), clicks.toList(), updates.toList())

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