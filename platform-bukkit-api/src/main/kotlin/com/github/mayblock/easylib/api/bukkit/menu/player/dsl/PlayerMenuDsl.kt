package com.github.mayblock.easylib.api.bukkit.menu.player.dsl

import com.github.mayblock.easylib.api.bukkit.menu.player.InteractHandler
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

@DslMarker
annotation class PlayerMenuDsl

@PlayerMenuDsl
interface PlayerMenuScope {
    fun slot(slot: Int, item: ItemStack, metadata: ItemMeta.() -> Unit = {}, onInteract: InteractHandler? = null)
}

fun PlayerMenuScope.slot(
    slot: Int,
    type: Material,
    amount: Int = 1,
    metadata: ItemMeta.() -> Unit = {},
    onInteract: InteractHandler? = null
) {
    slot(slot, ItemStack(type, amount), metadata, onInteract)
}