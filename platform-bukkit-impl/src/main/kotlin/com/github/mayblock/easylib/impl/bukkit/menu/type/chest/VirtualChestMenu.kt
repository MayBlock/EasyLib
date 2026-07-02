package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.menu.*
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import com.github.mayblock.easylib.packetevents.packet.ContainerType
import com.github.mayblock.easylib.packetevents.packet.dsl.PacketScope
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
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
    private val hidePlayerInventory: Boolean = true,
) : AbstractVirtualMenu(taskScheduler, specs), ChestMenu, ChestClickRenderer {

    override val windowId = windowIdCounter.getAndIncrement()

    private val clickEngine = ChestClickEngine(
        menu = this,
        grid = grid,
        menuSize = type.size,
        hidePlayerInventory = hidePlayerInventory,
        hasPlaceableSlot = specs.values.any { it.placeable },
        scheduler = taskScheduler,
        publish = ::publish,
        renderer = this,
        isViewing = { it in activeViewers },
    )

    init {
        requirePlaceableVisible(hidePlayerInventory, specs)
        startMenu()
    }

    override fun open(player: Player) {
        check(!isDestroyed) { "this menu is destroyed!" }
        player.sendPackets {
            bundle {
                forPlayer {
                    containerOpen(windowId, ContainerType.getByTypeId(type.ordinal)!!, title)
                    syncMenuItems()
                    if (hidePlayerInventory) hidePlayerInventoryItems()
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
        clickEngine.onViewerRemoved(player)
        player.updateInventory()
    }

    // ── ChestClickRenderer ───────────────────────────────────────────

    override fun repaintSlot(index: Int) = repaint(index)

    override fun sendCursor(player: Player, item: org.bukkit.inventory.ItemStack?) {
        player.sendPackets {
            forPlayer { updateCursorItem(item?.takeUnless { it.isEmptyStack() }?.fromBukkit()) }
        }
    }

    override fun sendWindowSlotEmpty(player: Player, windowSlot: Int) {
        player.sendPackets { forPlayer { updateItem(windowId, windowSlot, ItemStack.EMPTY) } }
    }

    override fun resyncSlots(player: Player, slots: Collection<Int>) {
        val (menuArea, bottomArea) = slots.distinct().partition { it < type.size }
        player.sendPackets {
            forPlayer {
                menuArea.forEach { updateItem(windowId, it, grid.packetItem(it)) }
                if (hidePlayerInventory) bottomArea.forEach { updateItem(windowId, it, ItemStack.EMPTY) }
            }
        }
        if (!hidePlayerInventory && bottomArea.isNotEmpty()) player.updateInventory()
    }

    override fun resyncBottomAfterTransfer(player: Player, clickedWindowSlot: Int) {
        if (hidePlayerInventory) sendWindowSlotEmpty(player, clickedWindowSlot)
        else player.updateInventory()
    }

    override fun updatePlayerInventory(player: Player) {
        player.updateInventory()
    }

    // ── 包监听 ───────────────────────────────────────────────────────

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
                        if (packet.containerId != windowId && removeViewer(player)) {
                            clickEngine.onViewerRemoved(player)
                        }
                    }
                }
            }

            /**
             * 玩家直接断线（非关窗）时，服务端不会为纯虚拟菜单产生 CLOSE/CLICK 包，
             * 故在此驱动与关窗一致的清理：移除观察者并把其菜单源虚拟光标物品归还来源槽位
             * （广播给其余观察者），避免共享菜单永久丢失 movable 物品与 per-player 光标映射泄漏。
             * 不调用 updateInventory（玩家已离开）。
             */
            override fun onUserDisconnect(e: UserDisconnectEvent) {
                val uuid = e.user.uuid ?: return
                val player = activeViewers.firstOrNull { it.uniqueId == uuid } ?: return
                if (removeViewer(player)) clickEngine.onViewerRemoved(player)
            }
        })

    private fun handleCloseWindow(player: Player, packet: WrapperPlayClientCloseWindow): Boolean {
        if (packet.windowId != windowId) return false
        return removeViewer(player).ifTrue {
            clickEngine.onViewerRemoved(player)
            player.updateInventory()
        }
    }

    /** Netty 线程：仅做归属判断与快照调度；决策与副作用在主线程串行执行。 */
    private fun handleClickWindow(player: Player, packet: WrapperPlayClientClickWindow): Boolean {
        if (packet.windowId != windowId) return false
        if (player !in activeViewers) return false
        clickEngine.submit(
            player,
            ChestClickEngine.ClickSnapshot(
                windowSlot = packet.slot,
                button = packet.button,
                clickType = packet.windowClickType,
                involvedSlots = packet.hashedSlots.keys.toList(),
                bukkitClickType = packet.getBukkitClickType(),
            ),
        )
        return true
    }

    private fun PacketScope.PlayerPacketScope.syncMenuItems() {
        containerItems(windowId, 0, grid.packetItems(type.size))
    }

    private fun PacketScope.PlayerPacketScope.hidePlayerInventoryItems() {
        for (i in playerInventoryWindowSlots(type.size)) {
            containerSetSlot(windowId, 0, i, ItemStack.EMPTY)
        }
    }

    companion object {
        private val windowIdCounter = AtomicInteger(114514)
    }
}
