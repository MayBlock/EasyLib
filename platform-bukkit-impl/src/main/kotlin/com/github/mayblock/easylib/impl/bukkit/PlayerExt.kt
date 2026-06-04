package com.github.mayblock.easylib.impl.bukkit

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

fun Player.setProgressbar(float: Float) {
    this.exp = 0.99f * float
}

fun Collection<Player>.sendMessage(text: String) {
    this.forEach { it.sendMessage(text) }
}

fun Player.sendActionBar(text: String) {
    this.spigot().sendMessage(
        ChatMessageType.ACTION_BAR,
        TextComponent.fromLegacy(text)
    )
}

fun Collection<Player>.sendActionBar(text: String) {
    this.forEach { it.sendActionBar(text) }
}

fun Player.sendTitle(
    title: String? = null,
    subtitle: String? = null,
    fadeIn: Int = 20,
    stay: Int = 60,
    fadeOut: Int = 20,
) {
    this.sendTitle(title ?: "", subtitle, fadeIn, stay, fadeOut)
}

fun Collection<Player>.sendTitle(
    title: String? = null,
    subtitle: String? = null,
    fadeIn: Int = 20,
    stay: Int = 60,
    fadeOut: Int = 20,
) {
    this.forEach { it.sendTitle(title, subtitle, fadeIn, stay, fadeOut) }
}

@DslMarker
annotation class PlayerInventoryDsl

@PlayerInventoryDsl
class PlayerInventoryBuilder @PublishedApi internal constructor() {
    private val _contents = mutableListOf<ItemStack>()

    @PublishedApi
    internal val contents get() = _contents.toTypedArray()

    /**
     * 0-8 是快捷栏，9-35是主库存（9从背包的最左上角开始counting。从左往右，从上到下）
     * 36-39 是装甲栏。40是副手栏
     *
     * @param slot - 放置的位置
     * @param item - 在位置上放置的物品
     */
    fun setItem(slot: Int, item: ItemStack) {
        require(slot in 0..40) { "Item must be between 0 and 40." }
        _contents[slot] = item
    }

    fun clear() {
        _contents.clear()
    }
}

inline fun Player.setInventory(inventory: PlayerInventoryBuilder.() -> Unit) {
    this.inventory.apply {
        contents = PlayerInventoryBuilder().apply(inventory).contents
    }
}

fun Player.setInventory(inventory: Inventory) {
    this.inventory.apply {
        contents = inventory.contents
    }
}

fun Player.setInventory(items: List<ItemStack>) {
    this.inventory.apply {
        contents = items.toTypedArray()
    }
}