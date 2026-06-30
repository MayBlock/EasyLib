package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.menu.*
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import com.github.mayblock.easylib.packetevents.packet.ContainerType
import com.github.mayblock.easylib.packetevents.packet.dsl.PacketScope
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicInteger

internal class VirtualChestMenu(
    taskScheduler: TaskScheduler,
    override val title: Component,
    override val type: ChestMenuType,
    specs: Map<Int, SlotSpec>,
) : AbstractVirtualMenu(taskScheduler, specs), ChestMenu {

    override val windowId = windowIdCounter.getAndIncrement()

    init {
        startMenu()
    }

    override fun open(player: Player) {
        check(!isDestroyed) { "this menu is destroyed!" }
        player.sendPackets {
            bundle {
                forPlayer {
                    containerOpen(windowId, ContainerType.getByTypeId(type.ordinal)!!, title)
                    syncMenuItems()
                    hidePlayerInventoryItems()
                }
            }
        }
        addViewer(player)
    }

    override fun repaint(index: Int) {
        activeViewers.toList().forEach { player ->
            if (!player.isOnline) return@forEach
            player.sendPackets { forPlayer { updateItem(windowId, index, grid.packetItem(index)) } }
        }
    }

    override fun onClose(player: Player) {
        player.updateInventory()
    }

    override fun registerPacketListener(): Disposable =
        BukkitEasyLib.api.packetManager.registerListener(object : PacketListener {
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

            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (player !in activeViewers) return
                when (e.packetType) {
                    PacketType.Play.Server.OPEN_WINDOW -> {
                        val packet = WrapperPlayServerOpenWindow(e)
                        if (packet.containerId != windowId) removeViewer(player)
                    }
                }
            }
        })

    private fun handleCloseWindow(player: Player, packet: WrapperPlayClientCloseWindow): Boolean {
        if (packet.windowId != windowId) return false
        return removeViewer(player).ifTrue { player.updateInventory() }
    }

    private fun handleClickWindow(player: Player, packet: WrapperPlayClientClickWindow): Boolean {
        if (packet.windowId != windowId) return false
        if (player !in activeViewers) return false
        val involvedSlots = packet.hashedSlots.keys
        val clickType = packet.getBukkitClickType()
        involvedSlots.forEach { slot ->
            publish(InventoryClickEvent(this, player, slot, clickType))
        }
        player.sendPackets {
            forPlayer {
                updateCursorItem(null)
                involvedSlots.forEach { slot ->
                    updateItem(windowId, slot, grid.packetItem(slot))
                }
            }
        }
        return true
    }

    private fun PacketScope.PlayerPacketScope.syncMenuItems() {
        containerItems(windowId, 0, grid.packetItems(type.size))
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
