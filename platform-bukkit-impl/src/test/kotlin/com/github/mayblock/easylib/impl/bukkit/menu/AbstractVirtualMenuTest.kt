package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 不调用 startMenu 的最小实现：不触碰 PacketEvents/调度器，纯测 grid 访问与总线派发。 */
private class TestMenu(
    specs: Map<Int, SlotSpec>,
) : AbstractVirtualMenu(mockk<TaskScheduler>(relaxed = true), specs) {
    override val windowId = 1
    val repaints = mutableListOf<Int>()
    override fun repaint(index: Int) { repaints += index }
    override fun registerPacketListener(): Disposable = Disposable { }
    override fun open(player: Player) {}
    fun emit(event: MenuEvent) = publish(event)
}

class AbstractVirtualMenuTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun specOf(item: ItemStack, block: SlotBuilder<InventoryClickEvent>.() -> Unit = {}): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java).apply(block).build(item)

    @Test
    fun `getItem 返回声明槽位的当前物品`() {
        val menu = TestMenu(mapOf(3 to specOf(ItemStack(Material.STONE, 5))))
        assertEquals(Material.STONE, menu.getItem(3)!!.type)
        assertEquals(5, menu.getItem(3)!!.amount)
    }

    @Test
    fun `getItem 对未声明槽位与 AIR 槽位返回 null`() {
        val menu = TestMenu(mapOf(3 to specOf(ItemStack(Material.AIR))))
        assertNull(menu.getItem(3))
        assertNull(menu.getItem(4))
    }

    @Test
    fun `setItem 写入声明槽位并触发 repaint，null 等价 AIR`() {
        val menu = TestMenu(mapOf(3 to specOf(ItemStack(Material.AIR))))
        menu.setItem(3, ItemStack(Material.DIAMOND, 2))
        assertEquals(Material.DIAMOND, menu.getItem(3)!!.type)
        menu.setItem(3, null)
        assertNull(menu.getItem(3))
        assertEquals(listOf(3, 3), menu.repaints)
    }

    @Test
    fun `setItem 对未声明槽位抛 IllegalArgumentException`() {
        val menu = TestMenu(mapOf(3 to specOf(ItemStack(Material.AIR))))
        assertFailsWith<IllegalArgumentException> { menu.setItem(4, ItemStack(Material.DIAMOND)) }
    }

    @Test
    fun `onTake 声明经总线按 index 过滤派发`() {
        var takeCalls = 0
        val menu = TestMenu(
            mapOf(3 to specOf(ItemStack(Material.STONE)) { onTake { takeCalls++ } }),
        )
        val player = mockk<Player>(relaxed = true)
        menu.emit(SlotTakeEvent(menu, 3, player, ItemStack(Material.STONE), targetSlot = 0))
        menu.emit(SlotTakeEvent(menu, 4, player, ItemStack(Material.STONE), targetSlot = 0))
        assertEquals(1, takeCalls)
    }

    @Test
    fun `isEmptyStack 判定 null、AIR 与非空`() {
        assertTrue((null as ItemStack?).isEmptyStack())
        assertTrue(ItemStack(Material.AIR).isEmptyStack())
        assertTrue(!ItemStack(Material.STONE).isEmptyStack())
    }
}
