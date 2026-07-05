package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuOpenEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.event.EventListener
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

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

    val hideInventory: Boolean get() = hidePlayerInventory

    private var destroyed = false
    override val isDestroyed: Boolean get() = destroyed

    init {
        // 初始物品写入真实容器
        specs.forEach { (index, spec) -> if (!spec.item.isEmptyStack()) bukkitInventory.setItem(index, spec.item) }
        // slot 声明的点击处理器挂到菜单总线（按 index 过滤）
        specs.forEach { (index, spec) ->
            spec.clickHandlers.forEach { handler ->
                bus.subscribe(EventListener<SlotClickEvent>(handler.type, null, { if (index == this.index) handler.block(this) }, handler.priority))
            }
        }
    }

    override fun getInventory(): Inventory = bukkitInventory

    fun specOf(slot: Int): SlotSpec? = specs[slot]

    override fun open(player: Player) {
        check(!destroyed) { "this menu is destroyed!" }
        player.openInventory(bukkitInventory) // 触发 InventoryOpenEvent → 监听器 publishOpen
    }

    override fun getItem(index: Int): ItemStack? = bukkitInventory.getItem(index)?.takeUnless { it.isEmptyStack() }

    override fun setItem(index: Int, item: ItemStack?) {
        require(index in 0 until type.size) { "slot $index out of range [0, ${type.size})" }
        bukkitInventory.setItem(index, item ?: ItemStack(Material.AIR))
    }

    fun publish(event: MenuEvent) = bus.emit(event)
    fun publishOpen(player: Player) = bus.emit(MenuOpenEvent(this, player))
    fun publishClose(player: Player) = bus.emit(MenuCloseEvent(this, player))

    /** 仅测试用：直接派发一次 slot 点击事件，验证 index 过滤。 */
    fun fireClickForTest(player: Player, index: Int) =
        bus.emit(InventoryClickEvent(this, player, index, ClickType.LEFT))

    override fun destroy() {
        if (destroyed) return
        bukkitInventory.viewers.toList().forEach { it.closeInventory() }
        bus.unsubscribeAll()
        destroyed = true
    }

    val scheduler: TaskScheduler get() = taskScheduler
}
