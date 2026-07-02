package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.slot.*
import com.github.mayblock.easylib.packetevents.packet.dsl.PacketScope
import com.github.retrooper.packetevents.protocol.item.ItemStack

internal fun PacketScope.PlayerPacketScope.updateCursorItem(item: ItemStack?) {
    containerSetSlot(-1, 0, -1, item) // windowId -1代表是光标槽，以下代码清空当前光标持有的物品
}

internal fun PacketScope.PlayerPacketScope.updateItem(windowId: Int, slot: Int, item: ItemStack) {
    containerSetSlot(windowId, 0, slot, item)
}

/** Bukkit 物品「空」判定：null、AIR 系或数量非正。 */
internal fun org.bukkit.inventory.ItemStack?.isEmptyStack(): Boolean =
    this == null || type.isAir || amount <= 0
