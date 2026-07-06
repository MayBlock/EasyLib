package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.packetevents.packet.dsl.PacketScope
import com.github.retrooper.packetevents.protocol.item.ItemStack

/** 清空/改写光标槽（windowId -1 为光标）。 */
internal fun PacketScope.PlayerPacketScope.updateCursorItem(item: ItemStack?) {
    containerSetSlot(-1, 0, -1, item)
}

/** 改写指定窗口某槽物品。 */
internal fun PacketScope.PlayerPacketScope.updateItem(windowId: Int, slot: Int, item: ItemStack) {
    containerSetSlot(windowId, 0, slot, item)
}
