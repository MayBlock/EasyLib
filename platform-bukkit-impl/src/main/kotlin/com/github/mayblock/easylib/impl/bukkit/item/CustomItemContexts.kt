package com.github.mayblock.easylib.impl.bukkit.item

import com.github.mayblock.easylib.api.bukkit.item.CustomItem
import com.github.mayblock.easylib.api.bukkit.item.CustomItemClick
import com.github.mayblock.easylib.api.bukkit.item.CustomItemInteraction
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

internal class InteractionContext(
    override val event: PlayerInteractEvent,
    override val item: CustomItem,
) : CustomItemInteraction {

    override val player: Player get() = event.player

    override fun consume(amount: Int) {
        if (player.gameMode == GameMode.CREATIVE) return
        val stack = event.item ?: return
        val hand = event.hand ?: EquipmentSlot.HAND
        val remaining = stack.amount - amount
        if (remaining > 0) stack.amount = remaining
        else player.inventory.setItem(hand, null)   // 扣到 0：清空触发手槽位，不留幽灵物品
    }

    override fun cancel() { event.isCancelled = true }
}

internal class ClickContext(
    override val event: InventoryClickEvent,
    override val item: CustomItem,
) : CustomItemClick {

    override val player: Player get() = event.whoClicked as Player

    override fun consume(amount: Int) {
        if (player.gameMode == GameMode.CREATIVE) return
        val stack = event.currentItem ?: return
        val remaining = stack.amount - amount
        if (remaining > 0) stack.amount = remaining
        else event.currentItem = null
    }

    override fun cancel() { event.isCancelled = true }
}
