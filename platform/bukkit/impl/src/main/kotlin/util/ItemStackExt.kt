package com.github.mayblock.easylib.platform.bukkit.impl.util

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

internal fun ItemStack?.isEmptyStack(): Boolean =
    this == null || type.isAir || amount <= 0

internal fun stack(
    item: ItemStack
) = ItemStack(item)

internal fun stack(
    type: Material,
    amount: Int = 1,
) = ItemStack(type, amount)

internal inline fun stack(
    item: ItemStack,
    metadata: ItemMeta.() -> Unit
): ItemStack = item.meta(metadata)

internal inline fun stack(type: Material, amount: Int = 1, metadata: ItemMeta.() -> Unit = {}) =
    stack(ItemStack(type, amount), metadata)

inline fun ItemStack.meta(block: ItemMeta.() -> Unit) = this.meta<ItemMeta>(block)

@JvmName("metaWithType")
inline fun <reified T : ItemMeta> ItemStack.meta(block: T.() -> Unit): ItemStack {
    val meta = (this.itemMeta as? T)
        ?: throw IllegalArgumentException("this item's ItemMeta is not ${T::class.simpleName}")
    block(meta)
    if (!this.setItemMeta(meta)) {
        throw IllegalArgumentException("Not applicable to this material: ${this.type}")
    }
    return this
}