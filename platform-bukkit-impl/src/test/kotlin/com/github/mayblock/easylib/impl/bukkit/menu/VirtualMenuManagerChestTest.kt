package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.slot
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VirtualMenuManagerChestTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun manager() = VirtualMenuManager(mockk<TaskScheduler>(relaxed = true), MockBukkit.createMockPlugin())

    @Test fun `createChestMenu 产出真实容器菜单`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3) {
            page(Component.text("t")) { slot(0, Material.DIAMOND) }
        }
        assertTrue(menu is RealChestMenu)
        assertEquals(Material.DIAMOND, (menu as RealChestMenu).bukkitInventory.getItem(0)!!.type)
    }

    @Test fun `placeable 且 hide=true 构建期报错`() {
        val mgr = manager()
        assertFailsWith<IllegalArgumentException> {
            mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = true) {
                page(Component.text("t")) { slot(0, Material.AIR, placeable = true) }
            }
        }
    }
}
