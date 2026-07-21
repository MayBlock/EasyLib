package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.impl.bukkit.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.util.stack
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class SlotBuilderTest {

    // 构造 ItemStack 需要服务器注册表（26.x API + MockBukkit 4）：无服务器时静态初始化会崩溃
    // 且毒化同 fork 后续用例，故本类与仓库其他测试一致走 MockBukkit 生命周期。
    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun builder() = SlotBuilder(InventoryClickEvent::class.java)

    @Test
    fun `build 默认无任何 handler`() {
        val spec = builder().apply { item(Material.STONE) }.build()
        assertTrue(spec.handlers.isEmpty())
        assertFalse(spec.hasPlaceHandlers)
    }

    @Test
    fun `hasPlaceHandlers 在声明 onPlace 后为 true`() {
        val spec = builder().apply { item(Material.STONE); onPlace { } }.build()
        assertTrue(spec.hasPlaceHandlers)
    }

    @Test
    fun `onTake 与 onPlace 以对应事件类型收集为 SlotHandler`() {
        val spec = builder().apply {
            item(Material.STONE)
            onTake { }
            onPlace { }
        }.build()
        assertEquals(
            listOf<Class<*>>(SlotTakeEvent::class.java, SlotPlaceEvent::class.java),
            spec.handlers.map { it.type },
        )
    }

    @Test
    fun `take 回调抛异常时事件被置为取消且异常继续外抛`() {
        val spec = builder().apply {
            item(Material.STONE)
            onTake { throw IllegalStateException("boom") }
        }.build()
        // 显式先放行（isCancelled = false），验证异常路径把它重新按回取消——而不是仅依赖默认值。
        val event = SlotTakeEvent(mockk<Menu>(), 0, mockk<Player>(), stack(Material.STONE), isCancelled = false)
        assertFailsWith<IllegalStateException> { spec.handlers.single().block(event) }
        assertTrue(event.isCancelled)
    }

    @Test
    fun `place 回调抛异常时事件被置为取消且异常继续外抛`() {
        val spec = builder().apply {
            item(Material.STONE)
            onPlace { throw IllegalStateException("boom") }
        }.build()
        val event = SlotPlaceEvent(mockk<Menu>(), 0, mockk<Player>(), stack(Material.STONE), isCancelled = false)
        assertFailsWith<IllegalStateException> { spec.handlers.single().block(event) }
        assertTrue(event.isCancelled)
    }
}
