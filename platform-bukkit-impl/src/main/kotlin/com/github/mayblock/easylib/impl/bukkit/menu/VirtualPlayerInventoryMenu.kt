package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.player.InteractHandler
import com.github.mayblock.easylib.api.bukkit.menu.player.InteractionType
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerMenuItem
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib.Companion.api
import com.github.mayblock.easylib.impl.bukkit.menu.ext.updateCursorItem
import com.github.mayblock.easylib.impl.bukkit.menu.ext.updateItem
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import com.github.mayblock.easylib.packetevents.packet.PacketScope
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
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.entity.Player

class VirtualPlayerInventoryMenu internal constructor(
    slots: List<PlayerMenuItem?>
) : PlayerInventoryMenu {

    private data class VirtualPlayerMenuItem(
        val item: ItemStack,
        val onInteract: InteractHandler? = null
    ) {
        companion object {
            val EMPTY = VirtualPlayerMenuItem(ItemStack.EMPTY)
        }
    }

    private val slots: List<VirtualPlayerMenuItem> = slots.map {
        val (item, handler) = it ?: return@map VirtualPlayerMenuItem.EMPTY
        VirtualPlayerMenuItem(item.let(SpigotConversionUtil::fromBukkitItemStack), handler)
    }

    private var offListener: Disposable? = null

    private var _isDestroyed: Boolean = false
    override val isDestroyed: Boolean get() = _isDestroyed
    val activePlayers = mutableSetOf<Player>()

    override fun activate(player: Player) {
        if (_isDestroyed) throw IllegalStateException("this inventory is destroyed!")
        player.sendPackets {
            bundle {
                forPlayer {
                    syncMenuItems()
                }
            }
        }
        activePlayers.add(player)
    }

    override fun deactivate(player: Player): Boolean {
        if (_isDestroyed) throw IllegalStateException("this inventory is destroyed!")
        return activePlayers.remove(player).ifTrue {
            restoreItems(player)
        }
    }

    override fun destroy() {
        if (_isDestroyed) return
        _isDestroyed = true
        activePlayers.forEach(::deactivate)
        offListener?.dispose()
    }

    private fun restoreItems(player: Player) {
        player.updateInventory()
    }

    init {
        offListener = api.packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (!activePlayers.contains(player)) return
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
                if (!activePlayers.contains(player)) return
                e.isCancelled = when (e.packetType) {
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        packet.windowId == 0 // 仅拦截 PlayerInventory
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId == 0) {
                            packet.item = slots.getOrNull(packet.slot)?.item
                                ?.let(SpigotConversionUtil::fromBukkitItemStack)
                                ?: ItemStack.EMPTY
                        }
                        false
                    }
                    else -> false
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
        val involvedSlots = packet.hashedSlots.keys
        if (involvedSlots.none { it in slots.indices }) return false
        involvedSlots.forEach { slot ->
            slots.getOrNull(slot)?.onInteract?.invoke(
                player,
                InteractionType.Inventory(slot, packet.getBukkitClickType())
            )
        }
        player.sendPackets {
            forPlayer {
                updateCursorItem(null)
                involvedSlots.forEach { slot ->
                    val item = slots.getOrNull(slot)?.item ?: ItemStack.EMPTY
                    updateItem(0, slot, item)
                }
            }
        }
        return true
    }

    private fun handleInteract(player: Player, action: InteractionType.Interact.Action): Boolean {
        val heldItemSlot = player.inventory.heldItemSlot + 36
        slots.getOrNull(heldItemSlot)?.apply {
            onInteract?.invoke(player, InteractionType.Interact(heldItemSlot, action))?.apply {
                player.sendPackets {
                    forPlayer {
                        updateItem(0, heldItemSlot, item)
                    }
                }
            }
        }
        return true
    }

    private fun handleDropItem(player: Player, slot: Int, action: DiggingAction): Boolean {
        if (action != DiggingAction.DROP_ITEM && action != DiggingAction.DROP_ITEM_STACK) return false
        slots.getOrNull(slot)?.apply {
            player.sendPackets {
                forPlayer {
                    updateItem(0, slot, item)
                }
            }
        }
        return true
    }

    private fun PacketScope.PlayerPacketScope.syncMenuItems() {
        slots.forEachIndexed { slot, item ->
            containerSetSlot(
                0,
                0,
                slot,
                item.item
            )
        }
    }
}