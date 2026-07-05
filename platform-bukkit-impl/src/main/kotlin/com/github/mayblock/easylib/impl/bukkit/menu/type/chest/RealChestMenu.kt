package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuOpenEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.event.EventListener
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap

/**
 * 真实容器版箱子菜单：自身即 [InventoryHolder]，持一个真实 [Inventory]（多观看者共享）。
 * 点击/拖拽/开关由 [MenuInteractionListener] 按 holder 路由回本菜单处理（见 Task A4）。
 * 菜单级事件总线仅暴露订阅侧（[EventSource]），`emit` 内部持有。
 */
internal class RealChestMenu(
    private val taskScheduler: TaskScheduler,
    override val title: Component,
    override val type: ChestMenuType,
    private val specs: Map<Int, SlotSpec>,
    private val hidePlayerInventory: Boolean = true,
    private val bus: SimpleEventBus<MenuEvent> = SimpleEventBus(),
) : ChestMenu, InventoryHolder, EventSource<MenuEvent> by bus {

    val bukkitInventory: Inventory =
        Bukkit.createInventory(this, type.size, LegacyComponentSerializer.legacySection().serialize(title))

    private var destroyed = false
    override val isDestroyed: Boolean get() = destroyed

    private val updateTaskIds = mutableListOf<Int>()

    /** 当前正在观看本菜单且需屏蔽背包的玩家集合（线程安全）。 */
    private val hideViewers = ConcurrentHashMap.newKeySet<Player>()

    /**
     * hide=true 时注册的发包拦截订阅；destroy 时释放。
     * 使用懒初始化：首次打开（publishOpen）时触发，避免构造期访问 BukkitEasyLib.api，
     * 保持对无 BukkitEasyLib 环境（如 MockBukkit 单元测试）的兼容性。
     */
    private val hidePacketSubDelegate: Lazy<Disposable?> =
        lazy { if (hidePlayerInventory) registerHideListener() else null }
    private val hidePacketSub: Disposable? by hidePacketSubDelegate

    /**
     * 注册发包拦截：对 [hideViewers] 中的玩家，将容器窗口（windowId != 0）的
     * WINDOW_ITEMS / SET_SLOT 包中玩家背包区（>= type.size）的物品替换为空气。
     * 与 VirtualPlayerInventoryMenu 相同模式，但 windowId 判定相反。
     */
    private fun registerHideListener(): Disposable =
        BukkitEasyLib.api.packetManager.registerListener(object : PacketListener {
            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (player !in hideViewers) return
                when (e.packetType) {
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        if (packet.windowId == 0) return
                        val items = packet.items.toMutableList()
                        for (i in hiddenBottomIndices(type.size, items.size)) {
                            items[i] = com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                        }
                        packet.items = items
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId == 0) return
                        if (packet.slot >= type.size) {
                            packet.item = com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                        }
                    }
                }
            }
        })

    init {
        // placeable 槽位与背包隐藏互斥：构建期即报错，避免运行时永远无法放置
        require(!(hidePlayerInventory && specs.values.any { it.placeable })) {
            "placeable slots require hidePlayerInventory = false"
        }
        // 初始物品写入真实容器
        specs.forEach { (index, spec) -> if (!spec.item.isEmptyStack()) bukkitInventory.setItem(index, spec.item) }
        // slot 声明的点击处理器挂到菜单总线（按 index 过滤）
        specs.forEach { (index, spec) ->
            spec.clickHandlers.forEach { handler ->
                bus.subscribe(EventListener<SlotClickEvent>(handler.type, null, { if (index == this.index) handler.block(this) }, handler.priority))
            }
        }
        startUpdates()
    }

    override fun getInventory(): Inventory = bukkitInventory

    fun specOf(slot: Int): SlotSpec? = specs[slot]

    override fun open(player: Player) {
        check(!destroyed) { "this menu is destroyed!" }
        player.openInventory(bukkitInventory) // 触发 InventoryOpenEvent → 监听器 publishOpen
    }

    override fun getItem(index: Int): ItemStack? {
        require(index in 0 until type.size) { "slot $index out of range [0, ${type.size})" }
        return bukkitInventory.getItem(index)?.takeUnless { it.isEmptyStack() }
    }

    override fun setItem(index: Int, item: ItemStack?) {
        require(index in 0 until type.size) { "slot $index out of range [0, ${type.size})" }
        bukkitInventory.setItem(index, item ?: ItemStack(Material.AIR))
    }

    fun publishOpen(player: Player) {
        if (hidePlayerInventory) {
            hidePacketSub // 首次打开时触发懒初始化，注册发包拦截器
            hideViewers += player
        }
        bus.emit(MenuOpenEvent(this, player))
    }

    fun publishClose(player: Player) = bus.emit(MenuCloseEvent(this, player))

    /** 仅测试用：直接派发一次 slot 点击事件，验证 index 过滤。 */
    fun fireClickForTest(player: Player, index: Int) =
        bus.emit(InventoryClickEvent(this, player, index, ClickType.LEFT))

    override fun destroy() {
        if (destroyed) return
        bukkitInventory.viewers.toList().forEach { it.closeInventory() }
        stopUpdates()
        if (hidePacketSubDelegate.isInitialized()) hidePacketSub?.dispose()
        bus.unsubscribeAll()
        destroyed = true
    }

    /** 由 [MenuInteractionListener] 在主线程调用：按放行门决策处理一次点击。 */
    fun handleClick(e: org.bukkit.event.inventory.InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val rawSlot = e.rawSlot
        val isTop = rawSlot in 0 until type.size
        // 信息性 onClick（已声明的顶部槽，任意点击，先于门/转移事件）
        if (isTop && specs.containsKey(rawSlot)) {
            bus.emit(InventoryClickEvent(this, player, rawSlot, e.click))
        }
        val spec = if (isTop) specs[rawSlot] else null
        val decision = ChestSlotGate.decide(isTop, rawSlot, e.action, spec?.movable ?: false, spec?.placeable ?: false, hidePlayerInventory)
        when (decision) {
            is SlotDecision.Deny -> e.isCancelled = true
            is SlotDecision.AllowNative -> {}
            is SlotDecision.FireTake -> {
                val ev = SlotTakeEvent(this, decision.slot, player, (e.currentItem ?: ItemStack(Material.AIR)).clone(), targetSlot = -1)
                bus.emit(ev)
                if (ev.isCancelled) e.isCancelled = true
            }
            is SlotDecision.FirePlace -> {
                val ev = SlotPlaceEvent(this, decision.slot, player, (e.cursor ?: ItemStack(Material.AIR)).clone(), sourceSlot = -1)
                bus.emit(ev)
                if (ev.isCancelled) e.isCancelled = true
            }
            is SlotDecision.FireSwap -> {
                // swap 与 drag 的观察者回调应无副作用，因为 Bukkit 的原子性使某个观察者
                // 可能在稍后整体取消前已触发。此处短路：take 取消时不再派发 place。
                val take = SlotTakeEvent(this, decision.slot, player, (e.currentItem ?: ItemStack(Material.AIR)).clone(), targetSlot = -1)
                bus.emit(take)
                if (take.isCancelled) { e.isCancelled = true; return }
                val place = SlotPlaceEvent(this, decision.slot, player, (e.cursor ?: ItemStack(Material.AIR)).clone(), sourceSlot = -1)
                bus.emit(place)
                if (place.isCancelled) e.isCancelled = true
            }
            is SlotDecision.ShiftIntoMenu -> {
                e.isCancelled = true
                handleShiftIntoMenu(player, e)
            }
            else -> error("Unhandled SlotDecision: $decision")
        }
    }

    private fun handleShiftIntoMenu(player: Player, e: org.bukkit.event.inventory.InventoryClickEvent) {
        val source = e.currentItem?.takeUnless { it.isEmptyStack() } ?: return
        val placeable = specs.filterValues { it.placeable }.keys.sorted().map { it to bukkitInventory.getItem(it) }
        val plan = ShiftIntoMenuPlanner.plan(source, placeable)
        var placedTotal = 0
        for (p in plan) {
            val placing = source.clone().apply { amount = p.amount }
            val ev = SlotPlaceEvent(this, p.slot, player, placing.clone(), sourceSlot = e.slot)
            bus.emit(ev)
            if (ev.isCancelled) continue
            val existing = bukkitInventory.getItem(p.slot)
            if (existing == null || existing.isEmptyStack()) bukkitInventory.setItem(p.slot, placing)
            else existing.amount += p.amount
            placedTotal += p.amount
        }
        if (placedTotal > 0) {
            val remaining = source.amount - placedTotal
            e.currentItem = if (remaining <= 0) null else source.clone().apply { amount = remaining }
            player.updateInventory()
        }
    }

    /** 由监听器在主线程调用：按 spec §4.3 处理一次拖拽（仅向 placeable 顶部槽放行，否则整体取消）。 */
    fun handleDrag(e: org.bukkit.event.inventory.InventoryDragEvent) {
        val player = e.whoClicked as? Player ?: return
        val topRaw = e.rawSlots.filter { it in 0 until type.size }
        if (topRaw.isEmpty()) { // 仅在底部（玩家背包）内拖拽
            if (hidePlayerInventory) e.isCancelled = true
            return
        }
        if (topRaw.any { specs[it]?.placeable != true }) { e.isCancelled = true; return } // 触及不可放置顶部槽
        for (slot in topRaw) {
            val newItem = e.newItems[slot] ?: continue
            val ev = SlotPlaceEvent(this, slot, player, newItem.clone(), sourceSlot = -1)
            bus.emit(ev)
            if (ev.isCancelled) { e.isCancelled = true; return } // 原生拖拽只能整体取消
        }
    }

    /** 关窗/断线：publishClose + 若隐藏则从 hideViewers 移除并恢复真实背包显示。 */
    fun handleClose(player: Player) {
        hideViewers -= player
        publishClose(player)
        if (hidePlayerInventory) player.updateInventory()
    }

    private fun startUpdates() {
        specs.forEach { (index, spec) ->
            spec.updateRules.forEach { rule ->
                updateTaskIds += taskScheduler.scheduleTask {
                    trigger = rule.trigger
                    isAsync = false // 真实容器 setItem 必须主线程
                    onTick = {
                        val current = bukkitInventory.getItem(index) ?: ItemStack(Material.AIR)
                        val event = SlotUpdateEvent(this@RealChestMenu, index, current.clone()).apply(rule.block)
                        if (event.item != current) bukkitInventory.setItem(index, event.item)
                    }
                }
            }
        }
    }

    private fun stopUpdates() {
        updateTaskIds.forEach(taskScheduler::cancelTask)
        updateTaskIds.clear()
    }
}
