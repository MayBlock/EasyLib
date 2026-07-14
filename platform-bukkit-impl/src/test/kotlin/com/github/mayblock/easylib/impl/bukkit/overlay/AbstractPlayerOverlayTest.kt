package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayHideEvent
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.util.isEmptyStack
import com.github.mayblock.easylib.impl.bukkit.util.item
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

/** 不调用 startOverlay 的最小实现：不触碰 PacketEvents/调度器，纯测 grid 访问、总线派发、生命周期。 */
private class TestOverlay(
    specs: Map<Int, OverlaySlotSpec>,
) : AbstractPlayerOverlay(mockk<TaskScheduler>(relaxed = true), specs) {
    val repaints = mutableListOf<Int>()
    override fun repaint(index: Int) { repaints += index }
    override fun registerPacketListener(): Disposable = Disposable { }
    override fun show(player: Player) { addViewer(player) }
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
        val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE, 5)), 4 to specOf(item(Material.AIR))))
        assertEquals(Material.STONE, o.getItem(3)!!.type)
        assertEquals(5, o.getItem(3)!!.amount)
        assertNull(o.getItem(4))
        assertNull(o.getItem(9))
    }

    @Test
    fun `getItem 返回防御副本，改动返回值不波及 overlay 内部`() {
        val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE, 1))))
        o.getItem(3)!!.amount = 99 // 外部拿到后原地改
        assertEquals(1, o.getItem(3)!!.amount) // 内部不受影响
    }

    @Test
    fun `setItem 存入防御副本，改动入参不波及 overlay 内部`() {
        val o = TestOverlay(mapOf(3 to specOf(item(Material.AIR))))
        val input = item(Material.DIAMOND, 1)
        o.setItem(3, input)
        input.amount = 99 // 入参在 setItem 之后被外部改
        assertEquals(1, o.getItem(3)!!.amount) // 内部存的是副本，不受影响
    }

    @Test
    fun `setItem 写入声明槽并触发 repaint，null 等价 AIR`() {
        val o = TestOverlay(mapOf(3 to specOf(item(Material.AIR))))
        o.setItem(3, item(Material.DIAMOND, 2))
        assertEquals(Material.DIAMOND, o.getItem(3)!!.type)
        o.setItem(3, null)
        assertNull(o.getItem(3))
        assertEquals(listOf(3, 3), o.repaints)
    }

    @Test
    fun `setItem 对未声明槽抛 IllegalArgumentException`() {
        val o = TestOverlay(mapOf(3 to specOf(item(Material.AIR))))
        assertFailsWith<IllegalArgumentException> { o.setItem(4, item(Material.DIAMOND)) }
    }

    @Test
    fun `onClick 声明经总线按 index 过滤派发`() {
        var clicks = 0
        val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE)) { onClick { clicks++ } }))
        val player = mockk<Player>(relaxed = true)
        o.emit(OverlayClickEvent(o, 3, player, ClickType.LEFT))
        o.emit(OverlayClickEvent(o, 4, player, ClickType.LEFT))
        assertEquals(1, clicks)
    }

    @Test
    fun `onPlayerQuit 移除观察者并派发 OverlayHideEvent，幂等`() {
        val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE))))
        val p = mockk<Player>(relaxed = true)
        o.show(p)
        var hides = 0
        o.on { on<OverlayHideEvent> { hides++ } }
        o.onPlayerQuit(p)
        assertEquals(0, o.activeViewers.size)
        assertEquals(1, hides)
        o.onPlayerQuit(p) // 已不在观察者集合：不重复派发
        assertEquals(1, hides)
    }

    @Test
    fun `OverlayQuitListener 把断线玩家从所有覆盖层移除`() {
        val o1 = TestOverlay(mapOf(3 to specOf(item(Material.STONE))))
        val o2 = TestOverlay(mapOf(4 to specOf(item(Material.STONE))))
        val p = mockk<Player>(relaxed = true)
        o1.show(p)
        o2.show(p)
        val e = mockk<PlayerQuitEvent>()
        every { e.player } returns p
        OverlayQuitListener { listOf(o1, o2) }.onQuit(e)
        assertEquals(0, o1.activeViewers.size)
        assertEquals(0, o2.activeViewers.size)
    }

    @Test
    fun `destroy 后 isDestroyed 为真`() {
        val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE))))
        assertTrue(!o.isDestroyed)
        o.destroy()
        assertTrue(o.isDestroyed)
    }

    @Test
    fun `destroy 末尾触发 onDestroyed 回调，且只触发一次（幂等销毁）`() {
        val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE))))
        var calls = 0
        o.onDestroyed = { calls++ }
        o.destroy()
        assertEquals(1, calls)
        o.destroy() // 已销毁：destroy 提前返回，不重复触发
        assertEquals(1, calls)
    }

    @Test
    fun `hideIfViewing 对观察中的玩家移除观察者并派发 OverlayHideEvent`() {
        val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE))))
        val p = mockk<Player>(relaxed = true)
        o.show(p)
        var hides = 0
        o.on { on<OverlayHideEvent> { hides++ } }
        o.hideIfViewing(p)
        assertEquals(0, o.activeViewers.size)
        assertEquals(1, hides)
    }

    @Test
    fun `hideIfViewing 对非观察者是无操作`() {
        val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE))))
        val bystander = mockk<Player>(relaxed = true)
        var hides = 0
        o.on { on<OverlayHideEvent> { hides++ } }
        o.hideIfViewing(bystander)
        assertEquals(0, hides)
    }

    @Test
    fun `isEmptyStack 判定 null、AIR 与非空`() {
        assertTrue((null as ItemStack?).isEmptyStack())
        assertTrue(item(Material.AIR).isEmptyStack())
        assertTrue(!item(Material.STONE).isEmptyStack())
    }
}
