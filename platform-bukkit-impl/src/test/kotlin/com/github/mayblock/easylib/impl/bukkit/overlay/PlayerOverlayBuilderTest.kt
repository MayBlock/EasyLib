package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.slot
import io.mockk.mockk
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlayerOverlayBuilderTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private lateinit var captured: Map<Int, OverlaySlotSpec>

    private fun build(block: com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope.() -> Unit) =
        PlayerOverlayBuilder { slots -> captured = slots; mockk<PlayerOverlay>() }.apply(block).build()

    @Test
    fun `slot 声明透传为 OverlaySlotSpec，含 handler`() {
        build {
            slot(0, Material.DIAMOND) { onClick { } }
            slot(5, Material.STONE)
        }
        assertEquals(setOf(0, 5), captured.keys)
        assertEquals(Material.DIAMOND, captured[0]!!.item.type)
        assertEquals(listOf<Class<*>>(OverlayClickEvent::class.java), captured[0]!!.handlers.map { it.type })
        assertTrue(captured[5]!!.handlers.isEmpty())
    }

    @Test
    fun `range slot 共享同一 spec 铺满区间`() {
        build { slot(1..3, Material.PAPER) }
        assertEquals(setOf(1, 2, 3), captured.keys)
    }

    @Test
    fun `越界 slot 抛 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            build { slot(46, Material.STONE) } // OVERLAY_SIZE=46，合法区间 [0,46)
        }
    }
}
