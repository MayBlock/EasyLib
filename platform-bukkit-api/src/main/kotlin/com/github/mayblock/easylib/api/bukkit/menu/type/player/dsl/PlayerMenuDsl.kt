package com.github.mayblock.easylib.api.bukkit.menu.type.player.dsl

import com.github.mayblock.easylib.api.bukkit.menu.event.UpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.dsl.SlotEventCollectorScope
import com.github.mayblock.easylib.api.bukkit.menu.type.player.InteractEvent
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

@DslMarker
annotation class PlayerMenuDsl

@PlayerMenuDsl
interface PlayerMenuScope {
    fun slot(
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit = {},
        block: (SlotEventCollectorScope<InteractEvent, UpdateEvent>.() -> Unit)? = null
    )
}

fun PlayerMenuScope.slot(
    index: Int,
    type: Material,
    amount: Int = 1,
    metadata: ItemMeta.() -> Unit = {},
    block: (SlotEventCollectorScope<InteractEvent, UpdateEvent>.() -> Unit)? = null
) {
    slot(index, ItemStack(type, amount), metadata, block)
}