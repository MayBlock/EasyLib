package com.github.mayblock.easylib.api.bukkit.item

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
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
     * 若事件的 useItemInHand() 结果为 DENY（例如事件已被取消），不回调。
     */
    fun onInteract(block: CustomItemInteraction.() -> Unit)

    /**
     * 背包/容器中点击本物品（[InventoryClickEvent]）时回调。
     * 已被其他监听器取消的点击不回调。
     */
    fun onInventoryClick(block: CustomItemClick.() -> Unit)

    /**
     * 玩家丢弃本物品（[PlayerDropItemEvent]）时回调。未注册时默认允许丢弃。
     * 已被取消的丢弃事件不回调。注意：容器内对着槽位按 Q 丢弃会先触发 [onInventoryClick] 再触发本回调（取消该点击则本回调不触发）；把光标上的物品点击容器外丢弃则只触发本回调。
     */
    fun onDrop(block: CustomItemDrop.() -> Unit)

    /**
     * 玩家将本物品作为方块放置（[BlockPlaceEvent]）时回调。
     * 未注册时默认取消放置——防止可放置材质的自定义物品被放置后丢失 PDC 身份；
     * 注册即接管：handler 不调用 [CustomItemContext.cancel] 即允许放置。
     * 注意：放置方块时 [PlayerInteractEvent]（RIGHT_CLICK_BLOCK）先于 [BlockPlaceEvent] 触发，
     * [onInteract] 中不取消不影响本默认规则生效。
     * 已被取消的放置事件不回调（也不再重复取消）。
     */
    fun onBlockPlace(block: CustomItemBlockPlace.() -> Unit)
}

/**
 * 自定义物品事件回调作用域的公共基面：底层事件、触发玩家、所属自定义物品与取消能力。
 * 各子作用域按语义追加自己的能力（如 consume）。
 */
@CustomItemDsl
interface CustomItemContext<out E : Event> {
    val event: E
    val player: Player
    val item: CustomItem

    /** 取消底层事件。 */
    fun cancel()
}

/** [CustomItemScope.onInteract] 回调作用域。消耗/取消均为可选动作，需要才调用。 */
interface CustomItemInteraction : CustomItemContext<PlayerInteractEvent> {

    /** 从触发手对应的槽位扣除 [amount] 个；创造模式下不消耗，直接跳过；扣到 0 清空槽位。 */
    fun consume(amount: Int = 1)
}

/** [CustomItemScope.onInventoryClick] 回调作用域。 */
interface CustomItemClick : CustomItemContext<InventoryClickEvent> {

    /**
     * 从被点击槽位扣除 [amount] 个；创造模式下不消耗，直接跳过；扣到 0 清空槽位。
     * 调用即隐含 [cancel]：既然被点击的物品栈已被本回调消耗，必须取消底层点击事件，
     * 避免原版点击结算（挪动/拆分物品栈等）与本次消耗产生冲突或双重生效。
     */
    fun consume(amount: Int = 1)
}

/** [CustomItemScope.onDrop] 回调作用域。无 consume：物品已离开背包；[cancel] 使物品回到背包。 */
interface CustomItemDrop : CustomItemContext<PlayerDropItemEvent>

/** [CustomItemScope.onBlockPlace] 回调作用域。无 consume：原版放置成功自会扣减；[cancel] 保留手中物品。 */
interface CustomItemBlockPlace : CustomItemContext<BlockPlaceEvent>
