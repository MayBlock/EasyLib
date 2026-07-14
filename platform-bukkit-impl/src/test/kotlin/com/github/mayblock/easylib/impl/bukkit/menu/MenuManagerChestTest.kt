package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.slot
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuManagerChestTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun manager() = MenuManager(mockk<TaskScheduler>(relaxed = true), MockBukkit.createMockPlugin())

    @Test fun `createChestMenu 产出真实容器菜单`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3) {
            page(Component.text("t")) { slot(0, Material.DIAMOND) }
        }
        assertTrue(menu is RealChestMenu)
        assertEquals(Material.DIAMOND, menu.bukkitInventory.getItem(0)!!.type)
    }

    @Test fun `placeable 且 hide=true 构建期报错`() {
        val mgr = manager()
        assertFailsWith<IllegalArgumentException> {
            mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = true) {
                page(Component.text("t")) { slot(0, Material.AIR, placeable = true) }
            }
        }
    }

    @Test
    fun `关闭事件经监听器清理活跃菜单`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0, Material.DIAMOND) }
        } as RealChestMenu
        val p = server.addPlayer()
        val view = p.openInventory(menu.bukkitInventory)!!
        val listener = MenuInteractionListener(mgr)
        listener.onOpen(InventoryOpenEvent(view))
        assertTrue(mgr.hasActiveMenu(p))
        listener.onClose(InventoryCloseEvent(view))
        assertFalse(mgr.hasActiveMenu(p))
    }

    // ---- MenuCloseEvent 幂等保护（quit 兜底与正常关窗不重复派发）----
    //
    // 注意：manager() 构造时已把自己的 MenuInteractionListener 注册进 MockBukkit 的
    // PluginManager，因此这里通过 callEvent 触发真实事件派发链，而不是手动调用监听器方法。
    // p.openInventory(...) 在 MockBukkit 中会同步 callEvent(InventoryOpenEvent)，
    // 即 publishOpen 已由 manager 的监听器真实触发。

    @Test
    fun `正常关窗后再触发 quit 兜底，MenuCloseEvent 只派发一次`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0, Material.DIAMOND) }
        } as RealChestMenu
        var closes = 0
        menu.on { on<MenuCloseEvent> { closes++ } }
        val p = server.addPlayer()
        val view = p.openInventory(menu.bukkitInventory)!!

        // 正常关窗：服务端派发 InventoryCloseEvent → onClose → handleClose（第 1 次）
        server.pluginManager.callEvent(InventoryCloseEvent(view))
        // 断线兜底：此时玩家的 openInventory 仍指向菜单视图（上面是手工构造的事件，
        // 并未真正关闭视图），模拟「服务端在 quit 前已先触发过 InventoryCloseEvent」
        // 的双调场景 → onQuit → handleClose（第 2 次，应被幂等保护拦下）
        server.pluginManager.callEvent(PlayerQuitEvent(p, "quit"))

        assertEquals(1, closes, "正常关窗 + quit 兜底双调时 MenuCloseEvent 应只派发一次")
    }

    @Test
    fun `直接 quit（无正常关窗），MenuCloseEvent 恰好派发一次`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0, Material.DIAMOND) }
        } as RealChestMenu
        var closes = 0
        menu.on { on<MenuCloseEvent> { closes++ } }
        val p = server.addPlayer()
        p.openInventory(menu.bukkitInventory)!!

        server.pluginManager.callEvent(PlayerQuitEvent(p, "quit"))

        assertEquals(1, closes, "直接 quit 时 MenuCloseEvent 应恰好派发一次")
    }
}
