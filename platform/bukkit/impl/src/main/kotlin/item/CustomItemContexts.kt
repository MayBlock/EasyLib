package com.github.mayblock.easylib.base.impl.bukkit.item

import com.github.mayblock.easylib.api.bukkit.item.CustomItem
import com.github.mayblock.easylib.api.bukkit.item.CustomItemClick
import com.github.mayblock.easylib.api.bukkit.item.CustomItemConsume
import com.github.mayblock.easylib.api.bukkit.item.CustomItemContext
import com.github.mayblock.easylib.api.bukkit.item.CustomItemDrop
import com.github.mayblock.easylib.api.bukkit.item.CustomItemInteraction
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * 上下文公共骨架：持有事件与物品，统一实现 [cancel]。
 * 注意可见性必须是 internal（internal 子类不得暴露更受限的父类型，EXPOSED_SUPER_CLASS）。
 */
internal sealed class BaseContext<out E>(
    final override val event: E,
    final override val item: CustomItem,
) : CustomItemContext<E> where E : Event, E : Cancellable {

    final override fun cancel() { event.isCancelled = true }
}

internal class InteractionContext(
    event: PlayerInteractEvent,
    item: CustomItem,
) : BaseContext<PlayerInteractEvent>(event, item), CustomItemInteraction {

    override val player: Player get() = event.player

    override fun consume(amount: Int) {
        if (player.gameMode == GameMode.CREATIVE) return
        val stack = event.item ?: return
        val hand = event.hand ?: EquipmentSlot.HAND
        val remaining = stack.amount - amount
        if (remaining > 0) player.inventory.setItem(hand, stack.clone().apply { this.amount = remaining })
        else player.inventory.setItem(hand, null)   // 扣到 0：清空触发手槽位，不留幽灵物品
    }
}

internal class ClickContext(
    event: InventoryClickEvent,
    item: CustomItem,
) : BaseContext<InventoryClickEvent>(event, item), CustomItemClick {

    override val player: Player get() = event.whoClicked as Player

    override fun consume(amount: Int) {
        if (player.gameMode == GameMode.CREATIVE) return
        val stack = event.currentItem ?: return
        val remaining = stack.amount - amount
        if (remaining > 0) event.currentItem = stack.clone().apply { this.amount = remaining }
        else event.currentItem = null
        cancel()   // 已消耗被点击的物品栈：取消底层点击，避免原版点击逻辑二次生效
    }
}

internal class DropContext(
    event: PlayerDropItemEvent,
    item: CustomItem,
) : BaseContext<PlayerDropItemEvent>(event, item), CustomItemDrop {

    override val player: Player get() = event.player
}

internal class ConsumeContext(
    event: PlayerItemConsumeEvent,
    item: CustomItem,
) : BaseContext<PlayerItemConsumeEvent>(event, item), CustomItemConsume {

    override val player: Player get() = event.player
}
