package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.impl.bukkit.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.util.item
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.test.*

class SlotBuilderTest {

    private fun builder() = SlotBuilder(InventoryClickEvent::class.java)

    @Test
    fun `build 默认无任何 handler`() {
        val spec = builder().build(item(Material.STONE))
        assertTrue(spec.handlers.isEmpty())
        assertFalse(spec.hasPlaceHandlers)
    }

    @Test
    fun `hasPlaceHandlers 在声明 onPlace 后为 true`() {
        val spec = builder().apply { onPlace { } }.build(item(Material.STONE))
        assertTrue(spec.hasPlaceHandlers)
    }

    @Test
    fun `onTake 与 onPlace 以对应事件类型收集为 SlotHandler`() {
        val spec = builder().apply {
            onTake { }
            onPlace { }
        }.build(item(Material.STONE))
        assertEquals(
            listOf<Class<*>>(SlotTakeEvent::class.java, SlotPlaceEvent::class.java),
            spec.handlers.map { it.type },
        )
    }

    @Test
    fun `take 回调抛异常时事件被置为取消且异常继续外抛`() {
        val spec = builder().apply {
            onTake { throw IllegalStateException("boom") }
        }.build(item(Material.STONE))
        // 显式先放行（isCancelled = false），验证异常路径把它重新按回取消——而不是仅依赖默认值。
        val event = SlotTakeEvent(mockk<Menu>(), 0, mockk<Player>(), item(Material.STONE), targetSlot = 0, isCancelled = false)
        assertFailsWith<IllegalStateException> { spec.handlers.single().block(event) }
        assertTrue(event.isCancelled)
    }

    @Test
    fun `place 回调抛异常时事件被置为取消且异常继续外抛`() {
        val spec = builder().apply {
            onPlace { throw IllegalStateException("boom") }
        }.build(item(Material.STONE))
        val event = SlotPlaceEvent(mockk<Menu>(), 0, mockk<Player>(), item(Material.STONE), sourceSlot = 3, isCancelled = false)
        assertFailsWith<IllegalStateException> { spec.handlers.single().block(event) }
        assertTrue(event.isCancelled)
    }
}
