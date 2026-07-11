package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuOpenEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.util.item
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class RealChestMenuTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun spec(item: ItemStack) = SlotBuilder(InventoryClickEvent::class.java).build(item)

    private fun menu(specs: Map<Int, SlotSpec>) =
        RealChestMenu(mockk<TaskScheduler>(relaxed = true), Component.text("交易"), ChestMenuType.GENERIC_9X3, specs, hidePlayerInventory = false)

    @Test fun `真实容器尺寸与初始物品`() {
        val m = menu(mapOf(11 to spec(item(Material.DIAMOND, 3))))
        assertEquals(27, m.bukkitInventory.size)
        assertEquals(Material.DIAMOND, m.bukkitInventory.getItem(11)!!.type)
        assertEquals(3, m.bukkitInventory.getItem(11)!!.amount)
        assertNull(m.bukkitInventory.getItem(0))
    }

    @Test fun `getInventory 返回同一真实容器（holder 即菜单）`() {
        val m = menu(mapOf(0 to spec(item(Material.STONE))))
        assertEquals(m.bukkitInventory, m.inventory)
        assertEquals(m, m.bukkitInventory.holder)
    }

    @Test fun `getItem setItem 读写真实容器，AIR 视为空`() {
        val m = menu(mapOf(4 to spec(item(Material.AIR))))
        assertNull(m.getItem(4))
        m.setItem(4, item(Material.EMERALD, 2))
        assertEquals(Material.EMERALD, m.getItem(4)!!.type)
        m.setItem(4, null)
        assertNull(m.getItem(4))
    }

    @Test fun `setItem 越界抛异常`() {
        val m = menu(mapOf(0 to spec(item(Material.STONE))))
        assertFailsWith<IllegalArgumentException> { m.setItem(27, item(Material.STONE)) }
    }

    @Test fun `specOf 暴露 slot 声明`() {
        val s =
            SlotBuilder(InventoryClickEvent::class.java).build(item(Material.STONE), movable = true, placeable = true)
        val m = menu(mapOf(6 to s))
        assertEquals(true, m.specOf(6)!!.movable)
        assertNull(m.specOf(7))
    }

    @Test fun `publishOpen publishClose 走事件总线`() {
        val m = menu(mapOf(0 to spec(item(Material.STONE))))
        val events = mutableListOf<MenuEvent>()
        m.on { on<MenuOpenEvent> { events += this }; on<MenuCloseEvent> { events += this } }
        val p = server.addPlayer()
        m.publishOpen(p); m.publishClose(p)
        assertEquals(2, events.size)
    }

    @Test fun `声明的 onClick 经总线按 index 过滤`() {
        var clicks = 0
        val built =
            SlotBuilder(InventoryClickEvent::class.java).apply { onClick { clicks++ } }.build(item(Material.STONE))
        val m = menu(mapOf(2 to built))
        val p = server.addPlayer()
        m.fireClickForTest(p, 2) // 见实现：仅用于测试的 publish 包装
        m.fireClickForTest(p, 3)
        assertEquals(1, clicks)
    }
}
