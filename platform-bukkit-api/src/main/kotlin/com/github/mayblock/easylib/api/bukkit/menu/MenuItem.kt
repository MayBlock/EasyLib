package com.github.mayblock.easylib.api.bukkit.menu

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

typealias ClickHandler = (Player, InteractionType) -> Unit

data class MenuItem(
    val item: ItemStack,
    val onClick: ClickHandler
)

sealed class InteractionType(
    val slot: Int,
) {
    class Inventory(slot: Int, val type: ClickType) : InteractionType(slot)
    class Interact(
        slot: Int,
        val action: InteractAction
    ) : InteractionType(slot)
}

enum class InteractAction {
    LEFT_CLICK,
    RIGHT_CLICK,
}