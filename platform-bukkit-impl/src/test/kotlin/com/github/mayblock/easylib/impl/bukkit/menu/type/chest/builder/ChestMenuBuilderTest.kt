package com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder

import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.item
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
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
    fun `slot 透传 onTake onPlace 声明到 SlotSpec`() {
        val specs = buildSpecs {
            slot(0) { item(Material.STONE) }
            slot(1) { item(Material.DIAMOND); onTake { isCancelled = false } }
            slot(2) { onPlace { isCancelled = false } } // 不声明 item ⇒ 默认 AIR（空投入口惯用写法）
        }
        assertFalse(specs.getValue(0).hasPlaceHandlers)
        assertFalse(specs.getValue(1).hasPlaceHandlers)
        assertTrue(specs.getValue(2).hasPlaceHandlers)
    }

    @Test
    fun `range 重载对每个槽位共享同一 SlotSpec 声明`() {
        val specs = buildSpecs { slot(3..5) { item(Material.STONE); onPlace { isCancelled = false } } }
        assertEquals(setOf(3, 4, 5), specs.keys)
        assertTrue(specs.values.all { it.hasPlaceHandlers })
    }
}
