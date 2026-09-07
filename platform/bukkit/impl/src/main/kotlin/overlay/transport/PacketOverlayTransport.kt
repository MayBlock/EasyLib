package com.github.mayblock.easylib.platform.bukkit.impl.overlay.transport

import com.github.mayblock.easylib.base.api.util.Disposable
import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.packetevents.api.packet.dsl.PacketBuilderScope
import com.github.mayblock.easylib.packetevents.api.packet.dsl.PacketScope
import com.github.mayblock.easylib.platform.bukkit.api.overlay.PlayerOverlay
import com.github.mayblock.easylib.platform.bukkit.api.overlay.slot.event.OverlaySlotActionEvent.Interact
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.execute
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.OverlayView
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.transport.OverlayTransport.Callbacks
import com.github.mayblock.easylib.platform.bukkit.impl.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.platform.bukkit.impl.util.sendPackets
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 基于数据包的 [OverlayTransport] 实现（覆盖玩家自身窗口 windowId=0）：
 * 出站改写 WINDOW_ITEMS/SET_SLOT 用虚拟物品遮罩真实背包；入站拦截点击/挥动/使用/丢弃并回调 [Callbacks]。
 * 唯一生产实现；overlay 协调者（[com.github.mayblock.easylib.platform.bukkit.impl.overlay.PlayerOverlayImpl]）不感知任何包细节。
 */
internal class PacketOverlayTransport(
    private val view: OverlayView,
    private val packetManager: PacketManager<Player>,
    private val sync: BukkitExecutionContext.Sync,
) : OverlayTransport {

    private companion object {
        const val WINDOW_ID = 0
    }

    private class HeldSlot(var slot: Int? = null)
    private val heldSlots = mutableMapOf<UUID, HeldSlot>()
    @Volatile private var disposed = false
    @Volatile private var callbacks: Callbacks? = null

    override fun prepare(player: Player, ready: () -> Unit) {
        val id = player.uniqueId
        val pending = synchronized(heldSlots) {
            if (disposed) null else HeldSlot().also { heldSlots[id] = it }
        }
        if (pending == null) {
            ready()
            return
        }
        sync.execute {
            try {
                val current = callbacks?.isViewer(player) == true && synchronized(heldSlots) {
                    !disposed && heldSlots[id] === pending
                }
                if (current) {
                    val slot = player.inventory.heldItemSlot
                    synchronized(heldSlots) {
                        // 较新的选槽包优先；旧 show 的初始化不能恢复已清理或替换的快照。
                        if (!disposed && heldSlots[id] === pending && pending.slot == null) pending.slot = slot
                    }
                } else {
                    synchronized(heldSlots) {
                        if (heldSlots[id] === pending) heldSlots.remove(id)
                    }
                }
            } finally {
                ready()
            }
        }
    }

    override fun forget(player: Player) {
        synchronized(heldSlots) { heldSlots.remove(player.uniqueId) }
    }

    override fun paintAll(player: Player) {
        player.sendOverlayPackets { forPlayer { syncOverlayItems(player) } }
    }

    override fun paint(player: Player, slot: Int) {
        player.sendOverlayPackets { forPlayer { updateItem(WINDOW_ID, slot, view.packetItem(player, slot)) } }
    }

    override fun restore(player: Player) {
        val id = player.uniqueId
        val previous = synchronized(heldSlots) { heldSlots[id] }
        sync.execute {
            if (callbacks?.isViewer(player) == true) return@execute
            synchronized(heldSlots) {
                if (heldSlots[id] === previous) heldSlots.remove(id)
            }
            player.updateInventory()
        }
    }

    override fun attach(callbacks: OverlayTransport.Callbacks): Disposable {
        this.callbacks = callbacks
        val subscription = packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (disposed || !callbacks.isViewer(player)) return
                // 只在我们判定需要拦截时才置 true；绝不把 isCancelled 写回 false——
                // 否则会撤销其他插件已经做出的取消决定（反取消他插件）。
                val shouldCancel = when (e.packetType) {
                    PacketType.Play.Client.HELD_ITEM_CHANGE -> {
                        if (!e.isCancelled) rememberHeldSlot(player, WrapperPlayClientHeldItemChange(e).slot)
                        false
                    }
                    PacketType.Play.Client.CLICK_WINDOW ->
                        handleClickWindow(player, WrapperPlayClientClickWindow(e), callbacks)
                    PacketType.Play.Client.ANIMATION ->
                        handleInteract(player, Interact.Action.LEFT_CLICK, callbacks)
                    PacketType.Play.Client.USE_ITEM ->
                        handleInteract(player, Interact.Action.RIGHT_CLICK, callbacks)
                    PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT ->
                        handleInteract(player, Interact.Action.RIGHT_CLICK, callbacks)
                    PacketType.Play.Client.INTERACT_ENTITY ->
                        // 26.1.2+（协议 775+）中攻击实体走独立的 ATTACK 包，本包只承载
                        // 右键交互（INTERACT/INTERACT_AT），故一律映射为右键。
                        handleInteract(player, Interact.Action.RIGHT_CLICK, callbacks)
                    PacketType.Play.Client.ATTACK ->
                        // 26.1.2+ 左键攻击实体的独立包：仅防护（取消 + 重发权威遮罩），不派发事件——
                        // LEFT_CLICK 事件统一由伴随每次左键的 ANIMATION 派发，避免一次点击触发两次。
                        guardHeldSlot(player)
                    PacketType.Play.Client.PLAYER_DIGGING -> {
                        val heldItemSlot = heldSlot(player)
                        heldItemSlot == null || handleDropItem(
                            player, heldItemSlot, WrapperPlayClientPlayerDigging(e).action, callbacks
                        )
                    }
                    PacketType.Play.Client.CREATIVE_INVENTORY_ACTION -> {
                        val packet = WrapperPlayClientCreativeInventoryAction(e)
                        // 覆盖层激活期间创造背包操作一律取消并重发权威遮罩，防止虚拟物品落入真实背包。
                        if (packet.slot in 0 until PlayerOverlay.OVERLAY_SIZE) {
                            player.sendOverlayPackets { forPlayer { updateItem(0, packet.slot, view.packetItem(player, packet.slot)) } }
                            true
                        } else false
                    }
                    else -> false
                }
                if (shouldCancel) e.isCancelled = true
            }

            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (disposed || !callbacks.isViewer(player)) return
                when (e.packetType) {
                    PacketType.Play.Server.HELD_ITEM_CHANGE -> {
                        if (!e.isCancelled) rememberHeldSlot(player, WrapperPlayServerHeldItemChange(e).slot)
                    }
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        if (packet.windowId != 0) return
                        packet.items = (0 until PlayerOverlay.OVERLAY_SIZE).map { view.packetItem(player, it) }
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId != 0) return
                        packet.item = view.packetItem(player, packet.slot)
                    }
                }
            }
        })
        return Disposable {
            synchronized(heldSlots) {
                if (disposed) return@Disposable
                disposed = true
                heldSlots.clear()
            }
            this.callbacks = null
            subscription.dispose()
        }
    }

    private fun rememberHeldSlot(player: Player, slot: Int) {
        if (slot !in 0..8) return
        synchronized(heldSlots) {
            if (!disposed) heldSlots[player.uniqueId]?.slot = slot
        }
    }

    private fun heldSlot(player: Player): Int? =
        synchronized(heldSlots) { heldSlots[player.uniqueId]?.slot?.plus(36) }

    private fun handleClickWindow(
        player: Player,
        packet: WrapperPlayClientClickWindow,
        callbacks: OverlayTransport.Callbacks,
    ): Boolean {
        if (packet.windowId != 0) return false
        if (packet.windowClickType == WrapperPlayClientClickWindow.WindowClickType.THROW) {
            val diggingAction = when (packet.button) {
                0 -> DiggingAction.DROP_ITEM
                1 -> DiggingAction.DROP_ITEM_STACK
                // 协议之外的畸形/未来客户端取值：吞掉这次点击（已取消），不让格式异常的包打垮监听器。
                else -> return true
            }
            return handleDropItem(player, packet.slot, diggingAction, callbacks)
        }
        val clickType = packet.getBukkitClickType()
        // 事件按「真实点击槽 ∪ 连带槽」并集逐槽派发：packet.slot 保证空手点空槽
        //（hashedSlots 为空）也能触发；involvedSlots 覆盖 shift / 数字键 swap 牵连到的目标槽。
        val clickedSlots = packet.hashedSlots.keys + packet.slot
        clickedSlots.forEach { slot ->
            callbacks.onClick(player, slot, clickType)
        }
        player.sendOverlayPackets {
            forPlayer {
                updateCursorItem(null)
                clickedSlots.forEach { slot ->
                    updateItem(0, slot, view.packetItem(player, slot))
                }
            }
        }
        return true
    }

    private fun handleInteract(
        player: Player,
        action: Interact.Action,
        callbacks: OverlayTransport.Callbacks,
    ): Boolean {
        // 首帧快照尚未就绪时立即取消，不在包处理线程等待主线程或猜测槽号。
        val heldItemSlot = heldSlot(player) ?: return true
        if (!view.isDeclared(heldItemSlot)) return false
        callbacks.onInteract(player, heldItemSlot, action)
        player.sendOverlayPackets { forPlayer { updateItem(0, heldItemSlot, view.packetItem(player, heldItemSlot)) } }
        return true
    }

    private fun handleDropItem(
        player: Player,
        slot: Int,
        action: DiggingAction,
        callbacks: OverlayTransport.Callbacks,
    ): Boolean {
        if (action == DiggingAction.START_DIGGING || action == DiggingAction.FINISHED_DIGGING) {
            // 左键方块挖掘：手持槽被声明时取消，防止用被遮罩的真实工具挖掘；
            // 事件仍由伴随的 ANIMATION 派发，此处不重复触发。
            return guardHeldSlot(player)
        }
        if (action == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
            // F 键交换主手/副手：只有当这两个窗口槽任一被覆盖层声明时才需要接管——
            // 未声明的槽面板不保护虚拟物品，放行真实交换即可。
            val heldItemSlot = heldSlot(player) ?: return true
            val offhandSlot = 45
            if (!view.isDeclared(heldItemSlot) && !view.isDeclared(offhandSlot)) return false
            player.sendOverlayPackets {
                forPlayer {
                    updateItem(0, heldItemSlot, view.packetItem(player, heldItemSlot))
                    updateItem(0, offhandSlot, view.packetItem(player, offhandSlot))
                }
            }
            return true
        }
        if (action != DiggingAction.DROP_ITEM && action != DiggingAction.DROP_ITEM_STACK) return false
        if (view.isDeclared(slot)) {
            player.sendOverlayPackets { forPlayer { updateItem(0, slot, view.packetItem(player, slot)) } }
        }
        return true
    }

    /** 手持槽被声明时取消动作并重发权威遮罩（防真实物品穿透），不派发事件。 */
    private fun guardHeldSlot(player: Player): Boolean {
        val heldItemSlot = heldSlot(player) ?: return true
        if (!view.isDeclared(heldItemSlot)) return false
        player.sendOverlayPackets { forPlayer { updateItem(0, heldItemSlot, view.packetItem(player, heldItemSlot)) } }
        return true
    }

    private fun Player.sendOverlayPackets(block: PacketBuilderScope.() -> Unit) {
        context(packetManager) {
            sendPackets(block)
        }
    }

    private fun PacketScope.PlayerPacketScope.syncOverlayItems(player: Player) {
        containerItems(0, 0, view.packetItems(player, PlayerOverlay.OVERLAY_SIZE))
    }

    /** 清空/改写光标槽（windowId -1 为光标）。 */
    private fun PacketScope.PlayerPacketScope.updateCursorItem(item: ItemStack?) {
        containerSetSlot(-1, 0, -1, item)
    }

    /** 改写指定窗口某槽物品。 */
    private fun PacketScope.PlayerPacketScope.updateItem(windowId: Int, slot: Int, item: ItemStack) {
        containerSetSlot(windowId, 0, slot, item)
    }
}
