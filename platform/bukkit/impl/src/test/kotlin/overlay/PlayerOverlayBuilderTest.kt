package com.github.mayblock.easylib.base.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope
import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.item
import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.onAction
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.base.impl.bukkit.overlay.builder.PlayerOverlayBuilder
import com.github.mayblock.easylib.base.impl.bukkit.overlay.slot.OverlaySlotSpec
import com.github.mayblock.easylib.base.impl.bukkit.util.stack
import io.mockk.mockk
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class PlayerOverlayBuilderTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private lateinit var captured: Map<Int, OverlaySlotSpec>

    private fun build(block: PlayerOverlayScope.() -> Unit) =
        PlayerOverlayBuilder { slots -> captured = slots; mockk<PlayerOverlay>() }.apply(block).build()

    @Test
    fun `slot 声明透传为 OverlaySlotSpec，含 handler`() {
        build {
            slot(0) {
                item(Material.DIAMOND)
                onAction { }
            }
            slot(5) {
                item(Material.STONE)
            }
        }
        assertEquals(setOf(0, 5), captured.keys)
        assertEquals(Material.DIAMOND, captured[0]!!.item.type)
        assertEquals(listOf<Class<*>>(OverlaySlotActionEvent::class.java), captured[0]!!.handlers.map { it.type })
        assertTrue(captured[5]!!.handlers.isEmpty())
    }

    @Test
    fun `range slot 共享同一 spec 铺满区间`() {
        build { slot(1..3) { item(Material.PAPER) } }
        assertEquals(setOf(1, 2, 3), captured.keys)
    }

    @Test
    fun `slot 的 item 以副本存入，metadata 应用于副本而非调用者对象`() {
        val template = stack(Material.DIAMOND, 1)
        build {
            slot(0) {
                item(template) {
                    isUnbreakable = true
                }
            }
        }
        assertFalse(template.itemMeta!!.isUnbreakable) // 调用者对象未被原地修改
        assertTrue(captured[0]!!.item.itemMeta!!.isUnbreakable) // metadata 生效在内部副本上
        template.amount = 99 // 声明后外部继续改模板
        assertEquals(1, captured[0]!!.item.amount) // 内部不受影响
    }

    @Test
    fun `越界 slot 抛 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            build {
                slot(46) {
                    item(Material.STONE)
                }
            } // OVERLAY_SIZE=46，合法区间 [0,46)
        }
    }
}
