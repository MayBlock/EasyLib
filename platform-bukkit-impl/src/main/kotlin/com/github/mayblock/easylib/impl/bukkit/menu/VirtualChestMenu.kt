package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.ClickHandler
import com.github.mayblock.easylib.api.bukkit.menu.InventoryMenuItem
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuType
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib.Companion.api
import com.github.mayblock.easylib.impl.bukkit.menu.ext.updateCursorItem
import com.github.mayblock.easylib.impl.bukkit.menu.ext.updateItem
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.packetevents.packet.ContainerType
import com.github.mayblock.easylib.packetevents.packet.PacketScope
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicInteger

class VirtualChestMenu internal constructor(
    override val title: Component,
    override val type: ChestMenuType,
    slots: List<InventoryMenuItem?>
) : ChestMenu {

    private val windowId = windowIdCounter.getAndIncrement()

    private data class VirtualChestMenuItem(
        val item: ItemStack,
        val onClick: ClickHandler? = null,
    ) {
        companion object {
            val EMPTY = VirtualChestMenuItem(ItemStack.EMPTY)
        }
    }

    private var offListener: Disposable? = null
    private val slots = slots.map {
        val (item, handler) = it ?: return@map VirtualChestMenuItem.EMPTY
        VirtualChestMenuItem(item.let(SpigotConversionUtil::fromBukkitItemStack), handler)
    }

    override fun open(player: Player) {
        player.sendPackets {
            bundle {
                forPlayer {
                    containerOpen(windowId, ContainerType.getByTypeId(type.ordinal)!!, title)
                    syncMenuItems()
                    hidePlayerInventoryItems()
                }
            }
        }
    }

    init {
        offListener = api.packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                when (e.packetType) {
                    PacketType.Play.Client.CLICK_WINDOW -> {
                        val player = e.getPlayer() as? Player ?: return
                        e.isCancelled = handleClickWindow(player, WrapperPlayClientClickWindow(e))
                    }
                    PacketType.Play.Client.CLOSE_WINDOW -> {
                        val player = e.getPlayer() as? Player ?: return
                        e.isCancelled = handleCloseWindow(player, WrapperPlayClientCloseWindow(e))
                    }
                }
            }
        })
    }

    private fun handleCloseWindow(player: Player, packet: WrapperPlayClientCloseWindow): Boolean {
        if (packet.windowId != windowId) return false
        player.updateInventory()
        return true
    }

    private fun handleClickWindow(player: Player, packet: WrapperPlayClientClickWindow): Boolean {
        if (packet.windowId != windowId) return false
        val involvedSlots = packet.hashedSlots.keys
        println("windowId=$windowId, hashedSlots=${packet.hashedSlots}, cursorSlot: ${packet.slot}")
        involvedSlots.forEach { slot ->
            slots.getOrNull(slot)?.onClick?.invoke(player, packet.getBukkitClickType())
        }
        player.sendPackets {
            forPlayer {
                updateCursorItem(null)
                involvedSlots.forEach { slot ->
                    val item = slots.getOrNull(slot)?.item ?: ItemStack.EMPTY
                    updateItem(windowId, slot, item)
                }
            }
        }
        return true
    }

    private fun PacketScope.PlayerPacketScope.syncMenuItems() {
        containerItems(
            windowId,
            0,
            slots.map { it.item }
        )
    }

    private fun PacketScope.PlayerPacketScope.hidePlayerInventoryItems() {
        for (i in type.size - 1 until type.size + 36) {
            containerSetSlot(windowId, 0, i, ItemStack.EMPTY)
        }
    }

    companion object {
        private val windowIdCounter = AtomicInteger(114514)
    }
}