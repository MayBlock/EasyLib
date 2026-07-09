package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 不调用 startOverlay 的最小实现：不触碰 PacketEvents/调度器，纯测 grid 访问、总线派发、生命周期。 */
private class TestOverlay(
    specs: Map<Int, OverlaySlotSpec>,
) : AbstractPlayerOverlay(mockk<TaskScheduler>(relaxed = true), specs) {
    val repaints = mutableListOf<Int>()
    override fun repaint(index: Int) { repaints += index }
    override fun registerPacketListener(): Disposable = Disposable { }
    override fun show(player: Player) {}
    override fun hide(player: Player): Boolean = false
    fun emit(event: OverlayEvent) = publish(event)
}

class AbstractPlayerOverlayTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun specOf(item: ItemStack, block: OverlaySlotBuilder.() -> Unit = {}): OverlaySlotSpec =
        OverlaySlotBuilder().apply(block).build(item)

    @Test
    fun `getItem 返回声明槽当前物品，未声明或 AIR 返回 null`() {
        val o = TestOverlay(mapOf(3 to specOf(ItemStack(Material.STONE, 5)), 4 to specOf(ItemStack(Material.AIR))))
        assertEquals(Material.STONE, o.getItem(3)!!.type)
        assertEquals(5, o.getItem(3)!!.amount)
        assertNull(o.getItem(4))
        assertNull(o.getItem(9))
    }

    @Test
    fun `getItem 返回防御副本，改动返回值不波及 overlay 内部`() {
        val o = TestOverlay(mapOf(3 to specOf(ItemStack(Material.STONE, 1))))
        o.getItem(3)!!.amount = 99 // 外部拿到后原地改
        assertEquals(1, o.getItem(3)!!.amount) // 内部不受影响
    }

    @Test
    fun `setItem 存入防御副本，改动入参不波及 overlay 内部`() {
        val o = TestOverlay(mapOf(3 to specOf(ItemStack(Material.AIR))))
        val input = ItemStack(Material.DIAMOND, 1)
        o.setItem(3, input)
        input.amount = 99 // 入参在 setItem 之后被外部改
        assertEquals(1, o.getItem(3)!!.amount) // 内部存的是副本，不受影响
    }

    @Test
    fun `setItem 写入声明槽并触发 repaint，null 等价 AIR`() {
        val o = TestOverlay(mapOf(3 to specOf(ItemStack(Material.AIR))))
        o.setItem(3, ItemStack(Material.DIAMOND, 2))
        assertEquals(Material.DIAMOND, o.getItem(3)!!.type)
        o.setItem(3, null)
        assertNull(o.getItem(3))
        assertEquals(listOf(3, 3), o.repaints)
    }

    @Test
    fun `setItem 对未声明槽抛 IllegalArgumentException`() {
        val o = TestOverlay(mapOf(3 to specOf(ItemStack(Material.AIR))))
        assertFailsWith<IllegalArgumentException> { o.setItem(4, ItemStack(Material.DIAMOND)) }
    }

    @Test
    fun `onClick 声明经总线按 index 过滤派发`() {
        var clicks = 0
        val o = TestOverlay(mapOf(3 to specOf(ItemStack(Material.STONE)) { onClick { clicks++ } }))
        val player = mockk<Player>(relaxed = true)
        o.emit(OverlayClickEvent(o, 3, player, ClickType.LEFT))
        o.emit(OverlayClickEvent(o, 4, player, ClickType.LEFT))
        assertEquals(1, clicks)
    }

    @Test
    fun `destroy 后 isDestroyed 为真`() {
        val o = TestOverlay(mapOf(3 to specOf(ItemStack(Material.STONE))))
        assertTrue(!o.isDestroyed)
        o.destroy()
        assertTrue(o.isDestroyed)
    }

    @Test
    fun `isEmptyStack 判定 null、AIR 与非空`() {
        assertTrue((null as ItemStack?).isEmptyStack())
        assertTrue(ItemStack(Material.AIR).isEmptyStack())
        assertTrue(!ItemStack(Material.STONE).isEmptyStack())
    }
}
