package com.github.mayblock.easylib.api.bukkit.menu.type.player

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

class InteractEvent(
    menu: Menu,
    player: Player,
    index: Int,
    val type: InteractionType
) : SlotClickEvent(menu, index, player)

sealed class InteractionType {
    class Inventory(val type: ClickType) : InteractionType()
    class Interact(val action: Action) : InteractionType() {
        enum class Action {
            LEFT_CLICK,
            RIGHT_CLICK,
        }
    }
}