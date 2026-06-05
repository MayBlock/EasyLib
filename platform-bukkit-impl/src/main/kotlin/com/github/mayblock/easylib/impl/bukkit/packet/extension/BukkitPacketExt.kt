package com.github.mayblock.easylib.impl.bukkit.packet.extension

import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import org.bukkit.event.inventory.ClickType

fun WrapperPlayClientClickWindow.getBukkitClickType(): ClickType = when (windowClickType) {
    WrapperPlayClientClickWindow.WindowClickType.PICKUP -> {
        when (button) {
            0 -> ClickType.LEFT
            1 -> ClickType.RIGHT
            else -> ClickType.UNKNOWN
        }
    }

    WrapperPlayClientClickWindow.WindowClickType.QUICK_MOVE -> {
        when (button) {
            0 -> ClickType.SHIFT_LEFT
            1 -> ClickType.SHIFT_RIGHT
            else -> ClickType.UNKNOWN
        }
    }

    WrapperPlayClientClickWindow.WindowClickType.SWAP -> ClickType.NUMBER_KEY
    WrapperPlayClientClickWindow.WindowClickType.CLONE -> ClickType.MIDDLE
    WrapperPlayClientClickWindow.WindowClickType.THROW -> {
        when (button) {
            0 -> ClickType.DROP
            1 -> ClickType.CONTROL_DROP
            else -> ClickType.UNKNOWN
        }
    }

    WrapperPlayClientClickWindow.WindowClickType.PICKUP_ALL -> ClickType.DOUBLE_CLICK
    else -> ClickType.UNKNOWN
}