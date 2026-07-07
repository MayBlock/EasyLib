package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import com.github.mayblock.easylib.packetevents.packet.dsl.PacketScope
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import org.bukkit.entity.Player

/**
 * 基于数据包的玩家背包覆盖层（覆盖玩家自身窗口 windowId=0）：
 * 出站改写 WINDOW_ITEMS/SET_SLOT 用虚拟物品遮罩真实背包；入站拦截点击/挥动/使用/丢弃并派发覆盖层事件。
 */
internal class PacketPlayerOverlay(
    taskScheduler: TaskScheduler,
    specs: Map<Int, OverlaySlotSpec>,
) : AbstractPlayerOverlay(taskScheduler, specs) {

    private val windowId = 0

    init {
        startOverlay()
    }

    override fun show(player: Player) {
        check(!isDestroyed) { "this overlay is destroyed!" }
        player.sendPackets { forPlayer { syncOverlayItems() } }
        addViewer(player)
    }

    override fun hide(player: Player): Boolean {
        check(!isDestroyed) { "this overlay is destroyed!" }
        return removeViewer(player).ifTrue { player.updateInventory() }
    }

    override fun onHide(player: Player) {
        player.updateInventory()
    }

    override fun repaint(index: Int) {
        activeViewers.toList().forEach { player ->
            if (!player.isOnline) return@forEach
            player.sendPackets { forPlayer { updateItem(windowId, index, grid.packetItem(index)) } }
        }
    }

    override fun registerPacketListener(): Disposable =
        BukkitEasyLib.api.packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (player !in activeViewers) return
                e.isCancelled = when (e.packetType) {
                    PacketType.Play.Client.CLICK_WINDOW ->
                        handleClickWindow(player, WrapperPlayClientClickWindow(e))
                    PacketType.Play.Client.ANIMATION ->
                        handleInteract(player, OverlayInteractEvent.Action.LEFT_CLICK)
                    PacketType.Play.Client.USE_ITEM ->
                        handleInteract(player, OverlayInteractEvent.Action.RIGHT_CLICK)
                    PacketType.Play.Client.PLAYER_DIGGING -> {
                        val heldItemSlot = player.inventory.heldItemSlot + 36
                        handleDropItem(player, heldItemSlot, WrapperPlayClientPlayerDigging(e).action)
                    }
                    else -> false
                }
            }

            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (player !in activeViewers) return
                when (e.packetType) {
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        if (packet.windowId != 0) return
                        packet.items = (0 until PlayerOverlay.OVERLAY_SIZE).map { grid.packetItem(it) }
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId != 0) return
                        packet.item = grid.packetItem(packet.slot)
                    }
                }
            }
        })

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
            publish(OverlayClickEvent(this, slot, player, clickType))
        }
        player.sendPackets {
            forPlayer {
                updateCursorItem(null)
                involvedSlots.forEach { slot ->
                    updateItem(0, slot, grid.packetItem(slot))
                }
            }
        }
        return true
    }

    private fun handleInteract(player: Player, action: OverlayInteractEvent.Action): Boolean {
        val heldItemSlot = player.inventory.heldItemSlot + 36
        if (grid[heldItemSlot] == null) return false
        publish(OverlayInteractEvent(this, heldItemSlot, player, action))
        player.sendPackets { forPlayer { updateItem(0, heldItemSlot, grid.packetItem(heldItemSlot)) } }
        return true
    }

    private fun handleDropItem(player: Player, slot: Int, action: DiggingAction): Boolean {
        if (action != DiggingAction.DROP_ITEM && action != DiggingAction.DROP_ITEM_STACK) return false
        if (grid[slot] != null) {
            player.sendPackets { forPlayer { updateItem(0, slot, grid.packetItem(slot)) } }
        }
        return true
    }

    private fun PacketScope.PlayerPacketScope.syncOverlayItems() {
        containerItems(0, 0, grid.packetItems(PlayerOverlay.OVERLAY_SIZE))
    }
}
