package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.impl.bukkit.overlay.builder.OverlaySlotBuilder
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.OverlayView
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.util.SlotDisplayMap
import com.github.mayblock.easylib.impl.bukkit.util.stack
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayViewTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test
    fun `isDeclared 只看声明，与显示层无关`() {
        val map = SlotMap(mapOf(4 to OverlaySlotBuilder().build(stack(Material.STONE, 1))))
        val view = OverlayView(map, SlotDisplayMap())

        assertTrue(view.isDeclared(4))
        assertFalse(view.isDeclared(9))
    }
}
