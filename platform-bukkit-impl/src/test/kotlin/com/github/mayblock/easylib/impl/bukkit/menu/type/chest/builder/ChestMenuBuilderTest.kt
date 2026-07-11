package com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.util.item
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class ChestMenuBuilderTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun buildSpecs(block: ChestMenuBuilder.() -> Unit): Map<Int, SlotSpec> {
        var captured: Map<Int, SlotSpec> = emptyMap()
        ChestMenuBuilder(ChestMenuType.GENERIC_9X3, Component.text("t")) { _, slots ->
            captured = slots
            mockk<ChestMenu>(relaxed = true)
        }.apply(block).build()
        return captured
    }

    @Test
    fun `slot 透传 movable 与 placeable 到 SlotSpec`() {
        val specs = buildSpecs {
            slot(0, item(Material.STONE))
            slot(1, item(Material.DIAMOND), movable = true)
            slot(2, item(Material.AIR), placeable = true)
        }
        assertFalse(specs.getValue(0).movable); assertFalse(specs.getValue(0).placeable)
        assertTrue(specs.getValue(1).movable); assertFalse(specs.getValue(1).placeable)
        assertFalse(specs.getValue(2).movable); assertTrue(specs.getValue(2).placeable)
    }

    @Test
    fun `range 重载对每个槽位透传 flag`() {
        val specs = buildSpecs { slot(3..5, item(Material.STONE), movable = true) }
        assertEquals(setOf(3, 4, 5), specs.keys)
        assertTrue(specs.values.all { it.movable })
    }
}
