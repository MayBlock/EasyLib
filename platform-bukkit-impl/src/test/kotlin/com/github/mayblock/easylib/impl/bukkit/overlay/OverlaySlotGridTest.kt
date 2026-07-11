package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.util.item
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class OverlaySlotGridTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun spec(block: OverlaySlotBuilder.() -> Unit = {}) =
        OverlaySlotBuilder().apply(block).build(item(Material.STONE))

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
        val diamond = item(Material.DIAMOND)
        s.item = diamond
        assertEquals(diamond, s.item) // 写入内容生效
        assertNotSame(diamond, s.item) // 写时克隆：存的是副本，外部引用改不到内部
    }

    @Test
    fun `LiveSlot 持有 spec 物品的独立副本，构建后改原对象不波及内部`() {
        val template = item(Material.STONE, 1)
        val s = LiveSlot(OverlaySlotBuilder().build(template))
        template.amount = 99 // 外部（DSL 调用者）仍握着原对象并原地改
        assertEquals(1, s.item.amount) // 内部副本不受影响，缓存 key 稳定
    }
}
