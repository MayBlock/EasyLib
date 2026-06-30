package com.github.mayblock.easylib.api.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

interface SlotEvent: MenuEvent {
    val index: Int
}

open class SlotClickEvent(
    final override val menu: Menu,
    final override val index: Int,
    val player: Player
): SlotEvent

open class SlotUpdateEvent(
    final override val menu: Menu,
    final override val index: Int,
    var item: ItemStack
): SlotEvent