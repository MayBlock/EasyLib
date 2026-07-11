package com.github.mayblock.easylib.impl.bukkit.util

import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib.Companion.api
import org.bukkit.Material
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

fun ItemStack?.isEmptyStack(): Boolean =
    this == null || type.isAir || amount <= 0

fun item(
    item: ItemStack,
    metadata: (ItemMeta.() -> Unit)? = null
): ItemStack = metadata?.let(item::meta) ?: item

fun item(type: Material, amount: Int = 1, metadata: (ItemMeta.() -> Unit)? = null) =
    item(ItemStack(type, amount), metadata)

fun ItemStack.meta(block: ItemMeta.() -> Unit) = this.meta<ItemMeta>(block)

@JvmName("metaWithType")
inline fun <reified T : ItemMeta> ItemStack.meta(block: T.() -> Unit): ItemStack {
    require(!this.type.isAir) { "Cannot set metadata on air item" }
    (this.itemMeta as? T)?.also(block)
        ?: throw IllegalArgumentException("this item's ItemMeta is not ${T::class.simpleName}")
    return this
}

fun ItemStack.onInteract(block: PlayerInteractEvent.() -> Unit) {
    api.itemExtensionApi.onInteract(this, block)
}