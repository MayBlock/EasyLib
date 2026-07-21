package com.github.mayblock.easylib.api.bukkit.menu.slot.dsl

import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

@DslMarker
annotation class SlotDsl

@SlotDsl
interface SlotScope<out C : SlotClickEvent> {

    fun item(item: ItemStack)
    fun item(type: Material, amount: Int = 1, metadata: (ItemMeta.() -> Unit)? = null)

    fun onClick(priority: Priority = Priority.DEFAULT, block: C.() -> Unit)

    /**
     * 显示更新规则（纯视觉，契约见 [SlotUpdateEvent]）：按 [trigger] 周期对每个观察者各触发一次，
     * 对 `displayItem` 的修改只影响该玩家看到的样子，不写回真实容器。
     * 同一槽位上 [trigger] 相等的规则合并为一个调度任务，按 [priority] 升序（小值先）串行执行。
     */
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: SlotUpdateEvent.() -> Unit)

    /**
     * 物品被从本槽位取出时的把关点（回调只把关/观察，不搬运物品，契约见 [SlotTakeEvent]）。
     * 事件默认取消：不注册本回调 ⇒ 永不放行；注册后须显式 `isCancelled = false` 才放行本次取出，
     * 可按条件动态决定。目前仅箱子菜单使用本 DSL 并派发该事件。
     */
    fun onTake(priority: Priority = Priority.DEFAULT, block: SlotTakeEvent.() -> Unit)

    /**
     * 玩家物品被放入本槽位时的把关点（回调只把关/观察，不搬运物品，契约见 [SlotPlaceEvent]）。
     * 事件默认取消：不注册本回调 ⇒ 永不放行；注册后须显式 `isCancelled = false` 才放行本次放入，
     * 可按条件动态决定。目前仅箱子菜单使用本 DSL 并派发该事件。
     */
    fun onPlace(priority: Priority = Priority.DEFAULT, block: SlotPlaceEvent.() -> Unit)
}
