package com.github.mayblock.easylib.base.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuOpenEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.item
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.base.api.event.on
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.base.impl.bukkit.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.base.impl.bukkit.util.stack
import com.github.mayblock.easylib.packetevents.api.PacketManager
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

    private fun spec(item: ItemStack) = SlotBuilder(InventoryClickEvent::class.java).apply { item(item) }.build()

    private fun menu(specs: Map<Int, SlotSpec>) =
        RealChestMenu(
            mockk<TaskScheduler>(relaxed = true),
            mockk<PacketManager<*>>(relaxed = true),
            Component.text("交易"),
            ChestMenuType.GENERIC_9X3,
            specs,
            hidePlayerInventory = false
        )

    @Test fun `真实容器尺寸与初始物品`() {
        val m = menu(mapOf(11 to spec(stack(Material.DIAMOND, 3))))
        assertEquals(27, m.inventory.size)
        assertEquals(Material.DIAMOND, m.inventory.getItem(11)!!.type)
        assertEquals(3, m.inventory.getItem(11)!!.amount)
        assertNull(m.inventory.getItem(0))
    }

    @Test fun `getInventory 返回同一真实容器（holder 即菜单）`() {
        val m = menu(mapOf(0 to spec(stack(Material.STONE))))
        assertEquals(m, m.inventory.holder)
    }

    @Test fun `getItem setItem 读写真实容器，AIR 视为空`() {
        val m = menu(mapOf(4 to spec(stack(Material.AIR))))
        assertNull(m.getItem(4))
        m.setItem(4, stack(Material.EMERALD, 2))
        assertEquals(Material.EMERALD, m.getItem(4)!!.type)
        m.setItem(4, null)
        assertNull(m.getItem(4))
    }

    @Test fun `setItem 越界抛异常`() {
        val m = menu(mapOf(0 to spec(stack(Material.STONE))))
        assertFailsWith<IllegalArgumentException> { m.setItem(27, stack(Material.STONE)) }
    }

    @Test fun `handleOpen handleClose 走事件总线`() {
        val m = menu(mapOf(0 to spec(stack(Material.STONE))))
        val events = mutableListOf<MenuEvent>()
        m.on { on<MenuOpenEvent> { events += this }; on<MenuCloseEvent> { events += this } }
        val p = server.addPlayer()
        m.handleOpen(p); m.handleClose(p)
        assertEquals(2, events.size)
    }

    @Test fun `声明的 onClick 经总线按 index 过滤`() {
        var clicks = 0
        val built =
            SlotBuilder(InventoryClickEvent::class.java).apply { item(Material.STONE); onClick { clicks++ } }.build()
        val m = menu(mapOf(2 to built, 3 to spec(stack(Material.DIAMOND))))
        val view = open(m)
        m.handleClick(clickEvent(view, 2))
        m.handleClick(clickEvent(view, 3))
        assertEquals(1, clicks)
    }

    private fun open(m: RealChestMenu): org.bukkit.inventory.InventoryView {
        val p = server.addPlayer()
        return p.openInventory(m.inventory)!!
    }

    private fun clickEvent(
        view: org.bukkit.inventory.InventoryView,
        rawSlot: Int,
    ): org.bukkit.event.inventory.InventoryClickEvent =
        org.bukkit.event.inventory.InventoryClickEvent(
            view,
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
            rawSlot,
            org.bukkit.event.inventory.ClickType.LEFT,
            org.bukkit.event.inventory.InventoryAction.PICKUP_ALL,
        )
}
