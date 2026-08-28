package com.github.mayblock.easylib.platform.bukkit.api.menu.slot.dsl

import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.util.Priority
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.SlotClickEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.SlotTakeEvent
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

@DslMarker
internal annotation class SlotDsl

@SlotDsl
interface SlotScope<out C : SlotClickEvent> {

    fun item(item: ItemStack)

    fun onClick(priority: Priority = Priority.DEFAULT, block: C.() -> Unit)

    /**
     * 显示更新规则（纯视觉，契约见 [SlotUpdateScope]）：按 [trigger] 周期对每个观察者各触发一次；
     * 同槽同 [trigger] 的规则合并为一个任务按 [priority] 升序串行。详见 `docs/menus.md`。
     */
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: SlotUpdateScope.() -> Unit)

    /**
     * 取出把关：[SlotTakeEvent] 默认取消，须显式 `isCancelled = false` 才放行；不注册 ⇒ 永不放行。
     * 回调只把关/观察，不搬运物品。详见 `docs/menus.md`。
     */
    fun onTake(priority: Priority = Priority.DEFAULT, block: SlotTakeEvent.() -> Unit)

    /**
     * 放入把关：[SlotPlaceEvent] 默认取消，须显式 `isCancelled = false` 才放行；不注册 ⇒ 永不放行。
     * 回调只把关/观察，不搬运物品；菜单须以 `hidePlayerInventory = false` 创建。详见 `docs/menus.md`。
     */
    fun onPlace(priority: Priority = Priority.DEFAULT, block: SlotPlaceEvent.() -> Unit)
}

/**
 * [SlotScope.onUpdate] 的事务上下文（非事件、不上总线）——显示层契约：
 * [displayItem] 初值为真实物品的克隆，修改**纯视觉**、只影响 [viewer] 所见、不写回容器，且每次触发从真实基底重算；
 * 需要真实变更请调用 `ChestMenu.setItem`。回调在主线程执行。完整契约见 `docs/menus.md`「显示更新规则」。
 */
@SlotDsl
interface SlotUpdateScope {
    /** 本条规则所属的槽位下标。 */
    val index: Int

    /** 本次计算面向的观察者：规则按 trigger 周期**对每个观察者各触发一次**。 */
    val viewer: Player

    /** 该槽面向 [viewer] 的下一帧物品：以真实物品的副本为初值，可原地改或整体替换。 */
    var displayItem: ItemStack
}

inline fun SlotScope<*>.item(item: ItemStack, metadata: ItemMeta.() -> Unit) = item.clone().also {
    it.itemMeta = it.itemMeta?.also(metadata)
}.let(::item)

fun SlotScope<*>.item(type: Material, amount: Int = 1) = this.item(ItemStack(type, amount))
inline fun SlotScope<*>.item(type: Material, amount: Int = 1, metadata: ItemMeta.() -> Unit) {
    ItemStack(type, amount).also {
        it.itemMeta = it.itemMeta?.also(metadata)
    }.let(::item)
}
