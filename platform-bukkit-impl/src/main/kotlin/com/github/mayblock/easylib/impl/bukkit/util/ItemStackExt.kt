package com.github.mayblock.easylib.impl.bukkit.util

import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib.Companion.api
import org.bukkit.Material
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

fun item(
    item: ItemStack,
    metadata: ItemMeta.() -> Unit = {},
): ItemStack = item.also { item ->
    item.itemMeta = item.itemMeta?.also(metadata)
}

fun item(type: Material, amount: Int = 1, metadata: ItemMeta.() -> Unit = {}) =
    item(ItemStack(type, amount), metadata)

fun ItemStack.onInteract(block: PlayerInteractEvent.() -> Unit) {
    api.itemExtensionApi.onInteract(this, block)
}