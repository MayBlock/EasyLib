package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.impl.bukkit.util.item
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.test.*

class SlotBuilderTest {

    private fun builder() = SlotBuilder(InventoryClickEvent::class.java)

    @Test
    fun `build 默认 movable 与 placeable 为 false`() {
        val spec = builder().build(item(Material.STONE))
        assertFalse(spec.movable)
        assertFalse(spec.placeable)
    }

    @Test
    fun `build 透传 movable 与 placeable`() {
        val spec = builder().build(item(Material.STONE), movable = true, placeable = true)
        assertTrue(spec.movable)
        assertTrue(spec.placeable)
    }

    @Test
    fun `onTake 与 onPlace 以对应事件类型收集为 ClickHandler`() {
        val spec = builder().apply {
            onTake { }
            onPlace { }
        }.build(item(Material.STONE))
        assertEquals(
            listOf<Class<*>>(SlotTakeEvent::class.java, SlotPlaceEvent::class.java),
            spec.clickHandlers.map { it.type },
        )
    }

    @Test
    fun `take 回调抛异常时事件被置为取消且异常继续外抛`() {
        val spec = builder().apply {
            onTake { throw IllegalStateException("boom") }
        }.build(item(Material.STONE))
        val event = SlotTakeEvent(mockk<Menu>(), 0, mockk<Player>(), item(Material.STONE), targetSlot = 0)
        assertFailsWith<IllegalStateException> { spec.clickHandlers.single().block(event) }
        assertTrue(event.isCancelled)
    }

    @Test
    fun `place 回调抛异常时事件被置为取消且异常继续外抛`() {
        val spec = builder().apply {
            onPlace { throw IllegalStateException("boom") }
        }.build(item(Material.STONE))
        val event = SlotPlaceEvent(mockk<Menu>(), 0, mockk<Player>(), item(Material.STONE), sourceSlot = 3)
        assertFailsWith<IllegalStateException> { spec.clickHandlers.single().block(event) }
        assertTrue(event.isCancelled)
    }
}
