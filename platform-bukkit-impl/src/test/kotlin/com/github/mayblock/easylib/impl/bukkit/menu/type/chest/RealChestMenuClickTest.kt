package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.MenuInteractionListener
import com.github.mayblock.easylib.impl.bukkit.menu.MenuManager
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.util.item
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent as ApiInventoryClickEvent
import org.bukkit.event.inventory.InventoryClickEvent as BukkitInventoryClickEvent

class RealChestMenuClickTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun spec(item: ItemStack, movable: Boolean = false, placeable: Boolean = false) =
        SlotBuilder(ApiInventoryClickEvent::class.java).build(item, movable, placeable)

    private fun menu(specs: Map<Int, SlotSpec>): RealChestMenu =
        RealChestMenu(mockk<TaskScheduler>(relaxed = true), Component.text("t"), ChestMenuType.GENERIC_9X3, specs, hidePlayerInventory = false)

    private fun open(m: RealChestMenu): Pair<Player, InventoryView> {
        val p = server.addPlayer()
        return p to p.openInventory(m.bukkitInventory)!!
    }

    private fun click(view: InventoryView, rawSlot: Int, action: InventoryAction, click: ClickType = ClickType.LEFT): BukkitInventoryClickEvent =
        BukkitInventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, click, action)

    @Test fun `不可变槽点击被取消`() {
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND))))
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `movable 槽取出触发 SlotTakeEvent 且不取消`() {
        var take = 0
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 2), movable = true)))
        m.on { on<SlotTakeEvent> { take++ } }
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertEquals(1, take)
        assertFalse(e.isCancelled)
    }

    @Test fun `onTake 取消则阻止取出`() {
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 2), movable = true)))
        m.on { on<SlotTakeEvent> { isCancelled = true } }
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `placeable 槽放入触发 SlotPlaceEvent`() {
        var place = 0
        val m = menu(mapOf(5 to spec(item(Material.AIR), placeable = true)))
        m.on { on<SlotPlaceEvent> { place++ } }
        val (p, view) = open(m)
        view.setCursor(item(Material.EMERALD, 1))
        val e = click(view, 5, InventoryAction.PLACE_ALL)
        m.handleClick(e)
        assertEquals(1, place)
        assertFalse(e.isCancelled)
    }

    @Test fun `信息性 onClick 对已声明槽触发`() {
        var clicks = 0
        val built =
            SlotBuilder(ApiInventoryClickEvent::class.java).apply { onClick { clicks++ } }.build(item(Material.BARRIER))
        val m = menu(mapOf(8 to built))
        val (_, view) = open(m)
        m.handleClick(click(view, 8, InventoryAction.PICKUP_ALL))
        assertEquals(1, clicks)
    }

    @Test fun `shift 入菜单只向 placeable 槽分发`() {
        val places = mutableListOf<Int>()
        val m = menu(mapOf(
            0 to spec(item(Material.STONE, 60), placeable = true), // 同类剩 4
            1 to spec(item(Material.DIAMOND)),                     // 不可放置
            2 to spec(item(Material.AIR), placeable = true),       // 空
        ))
        m.on { on<SlotPlaceEvent> { places += index } }
        val (p, view) = open(m)
        p.inventory.setItem(0, item(Material.STONE, 40)) // 底部第一格
        val rawBottom = m.bukkitInventory.size + 0 // 底部第一格的 rawSlot
        val e = click(view, rawBottom, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        m.handleClick(e)
        assertTrue(e.isCancelled) // 原生被取消，改手动分发
        assertEquals(listOf(0, 2), places) // 先填同类槽0（+4），再填空槽2（+36）
        assertEquals(64, m.bukkitInventory.getItem(0)!!.amount)
        assertEquals(36, m.bukkitInventory.getItem(2)!!.amount)
        assertEquals(Material.DIAMOND, m.bukkitInventory.getItem(1)!!.type) // 槽1（不可放置）未被污染
    }

    @Test fun `拖拽触及不可放置顶部槽则整体取消`() {
        val m = menu(mapOf(0 to spec(item(Material.AIR), placeable = true), 1 to spec(item(Material.DIAMOND))))
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 2))
        // 拖到 placeable 槽0 与不可放置槽1
        val newItems = mapOf(0 to item(Material.EMERALD, 1), 1 to item(Material.EMERALD, 1))
        val e = InventoryDragEvent(view, item(Material.AIR), item(Material.EMERALD, 2), false, newItems)
        m.handleDrag(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `拖拽仅触及 placeable 顶部槽则放行并逐槽 onPlace`() {
        var place = 0
        val m = menu(
            mapOf(
                0 to spec(item(Material.AIR), placeable = true),
                1 to spec(item(Material.AIR), placeable = true)
            )
        )
        m.on { on<SlotPlaceEvent> { place++ } }
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 2))
        val newItems = mapOf(0 to item(Material.EMERALD, 1), 1 to item(Material.EMERALD, 1))
        val e = InventoryDragEvent(view, item(Material.AIR), item(Material.EMERALD, 2), false, newItems)
        m.handleDrag(e)
        assertFalse(e.isCancelled)
        assertEquals(2, place)
    }

    @Test fun `FireSwap 触发 take 与 place，均不取消则放行`() {
        var take = 0; var place = 0
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 1), movable = true, placeable = true)))
        m.on { on<SlotTakeEvent> { take++ }; on<SlotPlaceEvent> { place++ } }
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 1))
        val e = click(view, 5, InventoryAction.SWAP_WITH_CURSOR)
        m.handleClick(e)
        assertEquals(1, take); assertEquals(1, place)
        assertFalse(e.isCancelled)
    }

    @Test fun `FireSwap 中 take 取消则取消 Bukkit 事件`() {
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 1), movable = true, placeable = true)))
        m.on { on<SlotTakeEvent> { isCancelled = true } }
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 1))
        val e = click(view, 5, InventoryAction.SWAP_WITH_CURSOR)
        m.handleClick(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `shift 分发中某槽 place 取消则跳过、扣除只计实际放入`() {
        val m = menu(mapOf(
            0 to spec(item(Material.AIR), placeable = true),
            1 to spec(item(Material.AIR), placeable = true),
        ))
        m.on { on<SlotPlaceEvent> { if (index == 0) isCancelled = true } }
        val (p, view) = open(m)
        p.inventory.setItem(0, item(Material.STONE, 100))
        val e = click(view, m.bukkitInventory.size + 0, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        m.handleClick(e)
        assertNull(m.bukkitInventory.getItem(0))               // slot0 取消 → 未放入
        assertEquals(36, m.bukkitInventory.getItem(1)!!.amount) // slot1 得 36（100 = 64+36，slot0 被跳过）
        assertEquals(64, e.currentItem!!.amount)                // 来源只扣实际放入的 36，剩 64
    }

    @Test fun `MenuInteractionListener 按 holder 路由点击到菜单`() {
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND)))) // 不可变槽
        // 监听器路由前会校验菜单归属（防止多 MenuManager 实例重复处理），
        // 因此这里显式把 m 挂到一个 manager 名下，再用同一 manager 构造监听器。
        val mgr = MenuManager(mockk<TaskScheduler>(relaxed = true), MockBukkit.createMockPlugin())
        m.owner = mgr
        val (_, view) = open(m)
        val listener = MenuInteractionListener(mgr)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        listener.onClick(e)
        assertTrue(e.isCancelled) // 经 holder 路由到 handleClick，不可变槽被取消
    }
}
