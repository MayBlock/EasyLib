package com.github.mayblock.easylib.impl.bukkit.menu.type.player

import com.github.mayblock.easylib.api.bukkit.menu.event.Slot
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdatableSlot
import com.github.mayblock.easylib.api.bukkit.menu.event.UpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.player.InteractEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.player.InteractionType
import com.github.mayblock.easylib.api.bukkit.menu.type.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.menu.*
import com.github.mayblock.easylib.impl.bukkit.menu.internal.InternalSlot
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import com.github.mayblock.easylib.packetevents.packet.dsl.PacketScope
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import org.bukkit.entity.Player

class VirtualPlayerInventoryMenu internal constructor(
    taskScheduler: TaskScheduler,
    slots: Map<Int, Slot>
) : PlayerInventoryMenu, VirtualMenu {

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

    override val windowId: Int = 0

    private var offListener: Disposable? = null

    private var _isDestroyed: Boolean = false
    override val isDestroyed: Boolean get() = _isDestroyed
    override val activeViewers = mutableSetOf<Player>()

    override fun activate(player: Player) {
        if (_isDestroyed) throw IllegalStateException("this inventory is destroyed!")
        player.sendPackets {
            forPlayer {
                syncMenuItems()
            }
        }
        activeViewers.add(player)
    }

    override fun deactivate(player: Player): Boolean {
        if (_isDestroyed) throw IllegalStateException("this inventory is destroyed!")
        return activeViewers.remove(player).ifTrue {
            restoreItems(player)
        }
    }

    override fun destroy() {
        if (_isDestroyed) return
        _isDestroyed = true
        activeViewers.forEach(::deactivate)
        slotUpdateScheduler.cancelAllTasks()
        offListener?.dispose()
    }

    private fun restoreItems(player: Player) {
        player.updateInventory()
    }

    init {
        offListener = BukkitEasyLib.api.packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (!activeViewers.contains(player)) return
                val isCancelled = when (e.packetType) {
                    PacketType.Play.Client.CLICK_WINDOW -> handleClickWindow(
                        player,
                        WrapperPlayClientClickWindow(e)
                    )
                    PacketType.Play.Client.ANIMATION -> {
                        handleInteract(player, InteractionType.Interact.Action.LEFT_CLICK)
                    }
                    PacketType.Play.Client.USE_ITEM -> {
                        handleInteract(player, InteractionType.Interact.Action.RIGHT_CLICK)
                    }
                    PacketType.Play.Client.PLAYER_DIGGING -> {
                        val heldItemSlot = player.inventory.heldItemSlot + 36
                        handleDropItem(player, heldItemSlot, WrapperPlayClientPlayerDigging(e).action)
                    }
                    else -> false
                }
                e.isCancelled = isCancelled
            }

            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (!activeViewers.contains(player)) return
                when (e.packetType) {
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        if (packet.windowId != 0) return
                        packet.items = this@VirtualPlayerInventoryMenu.slots.values.map { it.packetItem }
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId != 0) return
                        packet.item = this@VirtualPlayerInventoryMenu.slots[packet.slot]?.packetItem
                            ?: ItemStack.EMPTY
                    }
                }
            }
        })

    }

    private fun handleClickWindow(player: Player, packet: WrapperPlayClientClickWindow): Boolean {
        if (packet.windowId != 0) return false
        if (packet.windowClickType == WrapperPlayClientClickWindow.WindowClickType.THROW) {
            val diggingAction = when (packet.button) {
                0 -> DiggingAction.DROP_ITEM
                1 -> DiggingAction.DROP_ITEM_STACK
                else -> throw UnsupportedOperationException()
            }
            return handleDropItem(player, packet.slot, diggingAction)
        }
        val clickType = packet.getBukkitClickType()
        val involvedSlots = packet.hashedSlots.keys
        involvedSlots.forEach { slot ->
            slots[slot]?.native?.asClickSlot<InteractEvent>()?.clickListeners?.forEach {
                val event = InteractEvent(player, slot, InteractionType.Inventory(clickType))
                it.onClick(event)
            }
        }
        player.sendPackets {
            forPlayer {
                updateCursorItem(null)
                involvedSlots.forEach { slot ->
                    val item = slots[slot]?.packetItem ?: ItemStack.EMPTY
                    updateItem(0, slot, item)
                }
            }
        }
        return true
    }

    private fun handleInteract(player: Player, action: InteractionType.Interact.Action): Boolean {
        val heldItemSlot = player.inventory.heldItemSlot + 36
        val slot = slots[heldItemSlot] ?: return false
        slot.native?.asClickSlot<InteractEvent>()?.clickListeners?.forEach {
            val event = InteractEvent(player, heldItemSlot, InteractionType.Interact(action))
            it.onClick(event)
        }?.apply {
            player.sendPackets {
                forPlayer {
                    updateItem(0, heldItemSlot, slot.packetItem)
                }
            }
        }
        return true
    }

    private fun handleDropItem(player: Player, slot: Int, action: DiggingAction): Boolean {
        if (action != DiggingAction.DROP_ITEM && action != DiggingAction.DROP_ITEM_STACK) return false
        slots[slot]?.apply {
            player.sendPackets {
                forPlayer {
                    updateItem(0, slot, packetItem)
                }
            }
        }
        return true
    }

    private fun PacketScope.PlayerPacketScope.syncMenuItems() {
        containerItems(
            0,
            0,
            List(PlayerInventoryMenu.INVENTORY_SIZE) { slots[it]?.packetItem }
        )
    }
}