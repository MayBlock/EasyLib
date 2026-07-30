package com.github.mayblock.easylib.api.bukkit.item

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.meta.ItemMeta

@DslMarker
internal annotation class CustomItemDsl

/**
 * [CustomItemRegistry.define] 的定义作用域：定制外观、声明交互回调。
 */
@CustomItemDsl
interface CustomItemScope {

    /** 定制物品外观/元数据；可多次调用，按声明顺序应用。 */
    fun meta(block: ItemMeta.() -> Unit)

    /**
     * 玩家手持本物品交互（[PlayerInteractEvent]）时回调。
     * 回调对所有交互动作（左键/右键、方块/空气）均会触发，调用方需自行按 [PlayerInteractEvent.getAction] 过滤。
     */
    fun onInteract(block: CustomItemInteraction.() -> Unit)

    /** 背包/容器中点击本物品（[InventoryClickEvent]）时回调。 */
    fun onInventoryClick(block: CustomItemClick.() -> Unit)
}

/** [CustomItemScope.onInteract] 回调作用域。消耗/取消均为可选动作，需要才调用。 */
@CustomItemDsl
interface CustomItemInteraction {
    val event: PlayerInteractEvent
    val player: Player
    val item: CustomItem

    /** 从触发手对应的槽位扣除 [amount] 个；创造模式下不消耗，直接跳过；扣到 0 清空槽位。 */
    fun consume(amount: Int = 1)

    /** 取消底层事件。 */
    fun cancel()
}

/** [CustomItemScope.onInventoryClick] 回调作用域。 */
@CustomItemDsl
interface CustomItemClick {
    val event: InventoryClickEvent
    val player: Player
    val item: CustomItem

    /**
     * 从被点击槽位扣除 [amount] 个；创造模式下不消耗，直接跳过；扣到 0 清空槽位。
     * 调用即隐含 [cancel]：既然被点击的物品栈已被本回调消耗，必须取消底层点击事件，
     * 避免原版点击结算（挪动/拆分物品栈等）与本次消耗产生冲突或双重生效。
     */
    fun consume(amount: Int = 1)

    /** 取消底层事件。 */
    fun cancel()
}
