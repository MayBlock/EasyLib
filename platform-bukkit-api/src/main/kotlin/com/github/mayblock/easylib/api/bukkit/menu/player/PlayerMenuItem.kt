package com.github.mayblock.easylib.api.bukkit.menu.player

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

typealias InteractHandler = (Player, InteractionType) -> Unit

data class PlayerMenuItem(
    val item: ItemStack,
    val onInteract: InteractHandler?
)

sealed class InteractionType(
    val slot: Int,
) {
    class Inventory(slot: Int, val type: ClickType) : InteractionType(slot)
    class Interact(
        slot: Int,
        val action: Action
    ) : InteractionType(slot) {

        enum class Action {
            LEFT_CLICK,
            RIGHT_CLICK,
        }
    }
}