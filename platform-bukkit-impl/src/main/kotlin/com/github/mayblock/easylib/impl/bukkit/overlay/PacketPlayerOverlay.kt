package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
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
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction
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
        return removeViewer(player).ifTrue { onHide(player) }
    }

    override fun onHide(player: Player) {
        player.updateInventory()
    }

    override fun repaint(index: Int) {
        // 遍历前先快照一份 viewers；循环体内再复查一次 activeViewers——
        // 若在快照之后、发包之前该玩家已被 hide/quit 移除，避免向已不再观察的玩家补发鬼影包。
        activeViewers.toList().forEach { player ->
            if (!player.isOnline) return@forEach
            if (player !in activeViewers) return@forEach
            player.sendPackets {
                forPlayer {
                    updateItem(windowId, index, grid.packetItem(index))
                }
            }
        }
    }

    override fun registerPacketListener(): Disposable =
        BukkitEasyLib.api.packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (player !in activeViewers) return
                // 只在我们判定需要拦截时才置 true；绝不把 isCancelled 写回 false——
                // 否则会撤销其他插件已经做出的取消决定（反取消他插件）。
                val shouldCancel = when (e.packetType) {
                    PacketType.Play.Client.CLICK_WINDOW ->
                        handleClickWindow(player, WrapperPlayClientClickWindow(e))
                    PacketType.Play.Client.ANIMATION ->
                        handleInteract(player, OverlayInteractEvent.Action.LEFT_CLICK)
                    PacketType.Play.Client.USE_ITEM ->
                        handleInteract(player, OverlayInteractEvent.Action.RIGHT_CLICK)
                    PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT ->
                        handleInteract(player, OverlayInteractEvent.Action.RIGHT_CLICK)
                    PacketType.Play.Client.INTERACT_ENTITY ->
                        // 26.1.2+（协议 775+）中攻击实体走独立的 ATTACK 包，本包只承载
                        // 右键交互（INTERACT/INTERACT_AT），故一律映射为右键。
                        handleInteract(player, OverlayInteractEvent.Action.RIGHT_CLICK)
                    PacketType.Play.Client.ATTACK ->
                        // 26.1.2+ 左键攻击实体的独立包：仅防护（取消 + 重发权威遮罩），不派发事件——
                        // LEFT_CLICK 事件统一由伴随每次左键的 ANIMATION 派发，避免一次点击触发两次。
                        guardHeldSlot(player)
                    PacketType.Play.Client.PLAYER_DIGGING -> {
                        val heldItemSlot = player.inventory.heldItemSlot + 36
                        handleDropItem(player, heldItemSlot, WrapperPlayClientPlayerDigging(e).action)
                    }
                    PacketType.Play.Client.CREATIVE_INVENTORY_ACTION -> {
                        val packet = WrapperPlayClientCreativeInventoryAction(e)
                        // 覆盖层激活期间创造背包操作一律取消并重发权威遮罩，防止虚拟物品落入真实背包。
                        if (packet.slot in 0 until PlayerOverlay.OVERLAY_SIZE) {
                            player.sendPackets { forPlayer { updateItem(0, packet.slot, grid.packetItem(packet.slot)) } }
                            true
                        } else false
                    }
                    else -> false
                }
                if (shouldCancel) e.isCancelled = true
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
                // 协议之外的畸形/未来客户端取值：吞掉这次点击（已取消），不让格式异常的包打垮监听器。
                else -> return true
            }
            return handleDropItem(player, packet.slot, diggingAction)
        }
        val clickType = packet.getBukkitClickType()
        // 事件按「真实点击槽 ∪ 连带槽」并集逐槽派发：packet.slot 保证空手点空槽
        //（hashedSlots 为空）也能触发；involvedSlots 覆盖 shift / 数字键 swap 牵连到的目标槽。
        val clickedSlots = packet.hashedSlots.keys + packet.slot
        clickedSlots.forEach { slot ->
            publishOnMainThread(OverlayClickEvent(this, slot, player, clickType))
        }
        player.sendPackets {
            forPlayer {
                updateCursorItem(null)
                clickedSlots.forEach { slot ->
                    updateItem(0, slot, grid.packetItem(slot))
                }
            }
        }
        return true
    }

    private fun handleInteract(player: Player, action: OverlayInteractEvent.Action): Boolean {
        val heldItemSlot = player.inventory.heldItemSlot + 36
        if (grid[heldItemSlot] == null) return false
        publishOnMainThread(OverlayInteractEvent(this, heldItemSlot, player, action))
        player.sendPackets { forPlayer { updateItem(0, heldItemSlot, grid.packetItem(heldItemSlot)) } }
        return true
    }

    private fun handleDropItem(player: Player, slot: Int, action: DiggingAction): Boolean {
        if (action == DiggingAction.START_DIGGING || action == DiggingAction.FINISHED_DIGGING) {
            // 左键方块挖掘：手持槽被声明时取消，防止用被遮罩的真实工具挖掘；
            // 事件仍由伴随的 ANIMATION 派发，此处不重复触发。
            return guardHeldSlot(player)
        }
        if (action == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
            // F 键交换主手/副手：只有当这两个窗口槽任一被覆盖层声明时才需要接管——
            // 未声明的槽面板不保护虚拟物品，放行真实交换即可。
            val heldItemSlot = player.inventory.heldItemSlot + 36
            val offhandSlot = 45
            if (grid[heldItemSlot] == null && grid[offhandSlot] == null) return false
            player.sendPackets {
                forPlayer {
                    updateItem(0, heldItemSlot, grid.packetItem(heldItemSlot))
                    updateItem(0, offhandSlot, grid.packetItem(offhandSlot))
                }
            }
            return true
        }
        if (action != DiggingAction.DROP_ITEM && action != DiggingAction.DROP_ITEM_STACK) return false
        if (grid[slot] != null) {
            player.sendPackets { forPlayer { updateItem(0, slot, grid.packetItem(slot)) } }
        }
        return true
    }

    /** 手持槽被声明时取消动作并重发权威遮罩（防真实物品穿透），不派发事件。 */
    private fun guardHeldSlot(player: Player): Boolean {
        val heldItemSlot = player.inventory.heldItemSlot + 36
        if (grid[heldItemSlot] == null) return false
        player.sendPackets { forPlayer { updateItem(0, heldItemSlot, grid.packetItem(heldItemSlot)) } }
        return true
    }

    /**
     * 把覆盖层事件派发调度到主线程执行（`publish` 最终会跑到玩家侧的处理器代码，
     * 后者按约定运行在主线程；本方法自身在 netty 包处理线程调用，故需转发）。
     * resync 发包（上面的 sendPackets 调用）不受影响，仍在 netty 线程原地执行。
     */
    private fun publishOnMainThread(event: OverlayEvent) {
        scheduler.scheduleTask { onTick = { publish(event) } }
    }

    private fun PacketScope.PlayerPacketScope.syncOverlayItems() {
        containerItems(0, 0, grid.packetItems(PlayerOverlay.OVERLAY_SIZE))
    }
}
