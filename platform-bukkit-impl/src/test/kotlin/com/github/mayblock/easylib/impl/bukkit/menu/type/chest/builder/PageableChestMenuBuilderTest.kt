package com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder

import com.github.mayblock.easylib.packetevents.PacketManager
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.MenuManager
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.InventoryView
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.bukkit.event.inventory.InventoryClickEvent as BukkitInventoryClickEvent

/**
 * BUG 回归：分页菜单第 2 页起完全脱管——`MenuManager.createChestMenu` 之前只对
 * `PageableChestMenuBuilder.build()` 返回的第 1 页调用 `register`，导致后续分页既不出现在
 * `activeMenus`/`getViewers` 里，也不会被 `MenuManager.close()` 销毁。
 */
class PageableChestMenuBuilderTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun manager() = MenuManager(mockk<TaskScheduler>(relaxed = true), mockk<PacketManager<*>>(relaxed = true), MockBukkit.createMockPlugin())

    private fun click(view: InventoryView, rawSlot: Int, action: InventoryAction): BukkitInventoryClickEvent =
        BukkitInventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, ClickType.LEFT, action)

    @Test fun `分页菜单第 2 页仍由 manager 管理 —— Open 事件被订阅、close 时被销毁`() {
        val mgr = manager()
        val page1 = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("p1")) { slot(0) { item(Material.DIAMOND) } }
            page(Component.text("p2")) { slot(0) { item(Material.EMERALD) } }
        } as RealChestMenu

        val p = server.addPlayer()
        val view = p.openInventory(page1.inventory)!!
        // 翻到第 2 页：点击默认的下一页按钮（slot = size - 4）触发导航 onClick，
        // 该 onClick 内部调用 page2.open(player)。
        page1.handleClick(click(view, ChestMenuType.GENERIC_9X3.size - 4, InventoryAction.PICKUP_ALL))
        val page2 = p.openInventory.topInventory.holder as RealChestMenu
        assertNotSame(page1, page2, "翻页后玩家应打开的是第 2 页的真实容器")

        // ① 第 2 页的 Open 事件应被 manager 订阅（register 应覆盖所有分页，而不仅仅是第 1 页）
        page2.handleOpen(p)
        assertTrue(p in mgr.getViewers(page2), "第 2 页应被 MenuManager 追踪为活跃观察者")

        // ② MenuManager.close() 应销毁所有分页，而不仅仅是第 1 页
        mgr.close()
        assertTrue(page2.isDestroyed, "close() 后第 2 页应被销毁")
    }
}
