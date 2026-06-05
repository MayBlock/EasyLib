package com.github.mayblock.easylib.impl.bukkit.util

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.entity.Player

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