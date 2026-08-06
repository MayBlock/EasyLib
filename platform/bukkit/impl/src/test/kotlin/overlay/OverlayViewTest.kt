package com.github.mayblock.easylib.base.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.item
import com.github.mayblock.easylib.base.impl.bukkit.overlay.builder.OverlaySlotBuilder
import com.github.mayblock.easylib.base.impl.bukkit.overlay.slot.OverlayView
import com.github.mayblock.easylib.base.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.base.impl.bukkit.util.SlotDisplayMap
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class OverlayViewTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test
    fun `isDeclared 只看声明，与显示层无关`() {
        val map = SlotMap(mapOf(4 to OverlaySlotBuilder().apply {
            item(Material.STONE, 1)
        }.build()))
        val view = OverlayView(map, SlotDisplayMap())

        assertTrue(view.isDeclared(4))
        assertFalse(view.isDeclared(9))
    }
}
