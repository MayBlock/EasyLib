package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.event.Slot
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdatableSlot
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.menu.*
import com.github.mayblock.easylib.impl.bukkit.menu.internal.InternalSlot
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
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

class VirtualChestMenu internal constructor(
    taskScheduler: TaskScheduler,
    override val title: Component,
    override val type: ChestMenuType,
    slots: Map<Int, Slot>
) : ChestMenu, VirtualMenu {

    private val slots = slots.mapValues { InternalSlot(it.value) }
    private val slotUpdateScheduler = SlotUpdateScheduler(taskScheduler) { index, listener ->
        if (activeViewers.isEmpty()) return@SlotUpdateScheduler
        val slot = this.slots[index]!!
        val oldItem = slot.bukkitItem
        val event = UpdateEvent(index, oldItem.clone()).also(listener::onUpdate)
        if (event.item == oldItem) return@SlotUpdateScheduler
        (slot.native as UpdatableSlot<*>).item = event.item
        activeViewers.forEach { player ->
            if (!player.isOnline) {
                activeViewers.remove(player)
                return@forEach
            }
            player.sendPackets {
                forPlayer {
                    updateItem(windowId, index, slot.packetItem)
                }
            }
        }
    }

    init {
        slots.mapValues { it.value.asUpdatableSlot<UpdateEvent>() }.forEach { (index, slot) ->
            if (slot == null) return@forEach
            slotUpdateScheduler.schedule(index, slot)
        }
    }

    override val windowId = windowIdCounter.getAndIncrement()

    private var offListener: Disposable? = null

    override val activeViewers = mutableSetOf<Player>()

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
        activeViewers.add(player)
    }

    init {
        offListener = BukkitEasyLib.api.packetManager.registerListener(object : PacketListener {
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
                if (!activeViewers.contains(player)) return
                when (e.packetType) {
                    PacketType.Play.Server.OPEN_WINDOW -> {
                        val packet = WrapperPlayServerOpenWindow(e)
                        if (packet.containerId != windowId) {
                            activeViewers.remove(player)
                        }
                    }
                }
            }
        })
    }

    private fun handleCloseWindow(player: Player, packet: WrapperPlayClientCloseWindow): Boolean {
        if (packet.windowId != windowId) return false
        player.updateInventory()
        return activeViewers.remove(player)
    }

    private fun handleClickWindow(player: Player, packet: WrapperPlayClientClickWindow): Boolean {
        if (packet.windowId != windowId) return false
        if (!activeViewers.contains(player)) return false
        val involvedSlots = packet.hashedSlots.keys
        val clickType = packet.getBukkitClickType()
        involvedSlots.forEach { slot ->
            slots[slot]?.native?.asClickSlot<InventoryClickEvent>()?.clickListeners?.forEach {
                val event = InventoryClickEvent(player, slot, clickType)
                it.onClick(event)
            }
        }
        player.sendPackets {
            forPlayer {
                updateCursorItem(null)
                involvedSlots.forEach { slot ->
                    val item = slots[slot]?.packetItem ?: ItemStack.EMPTY
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
            List(type.size) { slots[it]?.packetItem }
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