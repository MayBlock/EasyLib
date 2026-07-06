package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class OverlaySlotGridTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun spec(block: OverlaySlotBuilder.() -> Unit = {}) =
        OverlaySlotBuilder().apply(block).build(ItemStack(Material.STONE))

    @Test
    fun `get 返回声明槽的 LiveSlot，未声明返回 null`() {
        val grid = SlotGrid(mapOf(2 to spec()))
        assertEquals(Material.STONE, grid[2]!!.item.type)
        assertNull(grid[5])
    }

    @Test
    fun `forEachUpdatable 只遍历带 update rule 的槽`() {
        val grid = SlotGrid(
            mapOf(
                1 to spec(),
                2 to spec { onUpdate(TaskScheduler.Trigger.Once) { } },
            )
        )
        val visited = mutableListOf<Int>()
        grid.forEachUpdatable { index, _ -> visited += index }
        assertEquals(listOf(2), visited)
    }

    @Test
    fun `LiveSlot item 可变且初值为 spec 物品`() {
        val s = LiveSlot(spec())
        assertEquals(Material.STONE, s.item.type)
        val diamond = ItemStack(Material.DIAMOND)
        s.item = diamond
        assertSame(diamond, s.item)
    }
}
