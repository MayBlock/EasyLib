package com.github.mayblock.easylib.platform.bukkit.impl.overlay

import com.github.mayblock.easylib.platform.bukkit.api.overlay.slot.dsl.item
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.builder.OverlaySlotBuilder
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.OverlayView
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.SlotMap
import com.github.mayblock.easylib.platform.bukkit.impl.util.SlotDisplayMap
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
