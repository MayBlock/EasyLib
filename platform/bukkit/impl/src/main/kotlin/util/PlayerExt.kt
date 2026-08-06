package com.github.mayblock.easylib.base.impl.bukkit.util

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.entity.Player

inline fun Collection<Player>.sendMessage(text: String, block: (Player) -> Unit = {}) {
    this.forEach {
        it.sendMessage(text)
        block(it)
    }
}

fun Player.sendActionBar(text: String) {
    this.spigot().sendMessage(
        ChatMessageType.ACTION_BAR,
        TextComponent.fromLegacy(text)
    )
}

inline fun Collection<Player>.sendActionBar(text: String, block: (Player) -> Unit = {}) {
    this.forEach {
        it.sendActionBar(text)
        block(it)
    }
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

inline fun Collection<Player>.sendTitle(
    title: String? = null,
    subtitle: String? = null,
    fadeIn: Int = 20,
    stay: Int = 60,
    fadeOut: Int = 20,
    block: (Player) -> Unit = {}
) {
    this.forEach {
        it.sendTitle(title, subtitle, fadeIn, stay, fadeOut)
        block(it)
    }
}