package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.InteractAction
import com.github.mayblock.easylib.api.bukkit.menu.InteractionType
import com.github.mayblock.easylib.api.bukkit.menu.MenuItem
import com.github.mayblock.easylib.api.bukkit.menu.VirtualPlayerInventoryMenu
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib.Companion.api
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.entity.Player

class VirtualPlayerInventoryMenuImpl internal constructor(
    slots: Map<Int, MenuItem>,
    private val canDrop: Boolean = false,
) : VirtualPlayerInventoryMenu {

    private var offListener: Disposable? = null

    private var _isDestroyed: Boolean = false
    override val isDestroyed: Boolean get() = _isDestroyed
    val activePlayers = mutableSetOf<Player>()
    override val slots = slots.toMutableMap()

    override fun activate(player: Player) {
        if (_isDestroyed) throw IllegalStateException("this inventory is destroyed!")
        hideRealItems(player)
        player.sendPackets {
            forPlayer {
                slots.forEach { (slot, item) ->
                    containerSetSlot(0, slot, SpigotConversionUtil.fromBukkitItemStack(item.item))
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

    private fun hideRealItems(player: Player) {
        player.sendPackets {
            bundle {
                forPlayer {
                    repeat(size - 1) { i ->
                        containerSetSlot(0, i, null)
                    }
                }
            }
        }
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
                        handleInteract(player, InteractAction.LEFT_CLICK)
                    }

                    PacketType.Play.Client.USE_ITEM -> {
                        handleInteract(player, InteractAction.RIGHT_CLICK)
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
                    PacketType.Play.Server.WINDOW_ITEMS -> true
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId == 0) {
                            packet.item = slots[packet.slot]?.item
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
        if (!involvedSlots.any { slots.contains(it) }) return false
        involvedSlots.forEach { slot ->
            slots[slot]?.onClick(player, InteractionType.Inventory(slot, packet.getBukkitClickType()))
        }
        player.sendPackets {
            forPlayer {
                containerSetSlot(-1, -1, null) // windowId -1代表是光标槽，以下代码清空当前光标持有的物品
                involvedSlots.forEach { slot ->
                    val item = slots[slot]?.item?.let(SpigotConversionUtil::fromBukkitItemStack)
                    containerSetSlot(0, slot, item)
                }
            }
        }
        return true
    }

    private fun handleInteract(player: Player, action: InteractAction): Boolean {
        val heldItemSlot = player.inventory.heldItemSlot + 36
        slots[heldItemSlot]?.apply {
            onClick(player, InteractionType.Interact(heldItemSlot, action))
            updateItem(player, heldItemSlot, item)
        }
        return true
    }

    private fun handleDropItem(player: Player, slot: Int, action: DiggingAction): Boolean {
        if (action != DiggingAction.DROP_ITEM && action != DiggingAction.DROP_ITEM_STACK) return false
        slots[slot]?.apply {
            val item = if (canDrop) {
                if (item.amount > 1 && action == DiggingAction.DROP_ITEM) {
                    item.apply {
                        amount -= 1
                    }
                } else {
                    slots.remove(slot) // 从slots中删除
                    null
                }
            } else item
            updateItem(player, slot, item)
        }
        return true // 始终取消丢弃包
    }

    private fun updateItem(player: Player, slot: Int, item: org.bukkit.inventory.ItemStack?) {
        player.sendPackets {
            forPlayer {
                containerSetSlot(0, slot, item?.let(SpigotConversionUtil::fromBukkitItemStack))
            }
        }
    }
}