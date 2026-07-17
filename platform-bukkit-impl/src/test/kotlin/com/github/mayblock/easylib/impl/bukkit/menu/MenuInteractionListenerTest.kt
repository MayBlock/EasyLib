package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.impl.bukkit.menu.listener.MenuInteractionListener
import com.github.mayblock.easylib.impl.bukkit.scheduler.BukkitTaskScheduler
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import com.github.mayblock.easylib.packetevents.PacketManager
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

/**
 * 监听器 UI 无关性验收：路由只认 [BukkitMenu] 接口，归属由 [MenuManager] 查自己的名册裁定，
 * 监听器与菜单对具体 UI 类型均零感知。
 * 用一个非 chest 的第二种 [BukkitMenu] 假实现（漏斗容器）证明：新增 UI 类型
 * 只需实现 [BukkitMenu]，无需改动 [com.github.mayblock.easylib.impl.bukkit.menu.listener.MenuInteractionListener]。
 */
class MenuInteractionListenerTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun manager() =
        MenuManager(
            mockk<BukkitTaskScheduler>(relaxed = true),
            mockk<PacketManager<*>>(relaxed = true),
            MockBukkit.createMockPlugin()
        )

    /** 非 chest 的第二种 UI：漏斗容器菜单，仅记录各 handle* 的调用。 */
    private class FakeHopperMenu(
        bus: SimpleEventBus<MenuEvent> = SimpleEventBus(),
    ) : BukkitMenu, EventSource<MenuEvent> by bus {

        private val inv: Inventory = Bukkit.createInventory(this, InventoryType.HOPPER)

        val opens = mutableListOf<Player>()
        val clicks = mutableListOf<InventoryClickEvent>()
        val drags = mutableListOf<InventoryDragEvent>()
        val closes = mutableListOf<Player>()

        override var isDestroyed: Boolean = false
            private set

        override fun getInventory(): Inventory = inv
        override fun open(player: Player) { player.openInventory(inv) }
        override fun destroy() { isDestroyed = true }

        override fun handleOpen(player: Player) { opens += player }
        override fun handleClick(e: InventoryClickEvent) { clicks += e }
        override fun handleDrag(e: InventoryDragEvent) { drags += e }
        override fun handleClose(player: Player) { closes += player }

        override fun getItem(index: Int): ItemStack? = inv.getItem(index)
        override fun setItem(index: Int, item: ItemStack?) { inv.setItem(index, item) }
    }

    @Test fun `同一监听器把 open click drag close 路由给非 chest 的 BukkitMenu 实现`() {
        val mgr = manager()
        val menu = FakeHopperMenu().also { mgr.register(it) }
        val listener = MenuInteractionListener(mgr)
        val p = server.addPlayer()
        val view = p.openInventory(menu.inventory)!!
        // openInventory 在 MockBukkit 中会真实 callEvent(InventoryOpenEvent)，
        // 已由 mgr 构造时注册的监听器路由过一次 handleOpen；清空后只断言手动路由那一次。
        menu.opens.clear()

        listener.onInvOpen(InventoryOpenEvent(view))
        listener.onInvClick(InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL))
        listener.onInvDrag(InventoryDragEvent(view, null, ItemStack(org.bukkit.Material.STONE), false, mapOf(0 to ItemStack(org.bukkit.Material.STONE))))
        listener.onInvClose(InventoryCloseEvent(view))

        assertEquals(listOf<Player>(p), menu.opens)
        assertEquals(1, menu.clicks.size)
        assertEquals(1, menu.drags.size)
        assertEquals(listOf<Player>(p), menu.closes)
    }

    @Test fun `quit 兜底也按接口路由到非 chest 实现`() {
        val mgr = manager()
        val menu = FakeHopperMenu().also { mgr.register(it) }
        val listener = MenuInteractionListener(mgr)
        val p = server.addPlayer()
        p.openInventory(menu.inventory)!!

        listener.onQuit(PlayerQuitEvent(p, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
        assertEquals(listOf<Player>(p), menu.closes)
    }

    @Test fun `归属其他 manager 的菜单不被本监听器路由（多 manager 防重复处理）`() {
        val mgr = manager()
        val other = manager()
        val menu = FakeHopperMenu().also { other.register(it) }
        val listener = MenuInteractionListener(mgr)
        val p = server.addPlayer()
        val view = p.openInventory(menu.inventory)!!

        listener.onInvClick(InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL))
        listener.onInvClose(InventoryCloseEvent(view))

        assertTrue(menu.clicks.isEmpty())
        assertTrue(menu.closes.isEmpty())
    }
}
