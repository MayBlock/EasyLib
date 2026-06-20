package com.github.mayblock.easylib.api.bukkit.menu.type.player

import com.github.mayblock.easylib.api.bukkit.menu.event.ClickEvent
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

class InteractEvent(
    player: Player,
    slot: Int,
    val type: InteractionType
) : ClickEvent(slot, player)

sealed class InteractionType {
    class Inventory(val type: ClickType) : InteractionType()
    class Interact(val action: Action) : InteractionType() {
        enum class Action {
            LEFT_CLICK,
            RIGHT_CLICK,
        }
    }
}