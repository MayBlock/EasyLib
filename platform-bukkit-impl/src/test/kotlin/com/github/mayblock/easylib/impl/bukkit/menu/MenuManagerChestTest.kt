package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.slot
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
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
}
