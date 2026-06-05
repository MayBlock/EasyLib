package com.github.mayblock.easylib.api.bukkit.extension

import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

interface ItemExtension {

    fun onInteract(item: ItemStack, block: PlayerInteractEvent.() -> Unit)
    fun onClick(holder: InventoryHolder, item: ItemStack, block: InventoryClickEvent.() -> Unit)
}