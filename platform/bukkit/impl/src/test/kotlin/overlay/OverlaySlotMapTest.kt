package com.github.mayblock.easylib.base.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.item
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.impl.bukkit.overlay.builder.OverlaySlotBuilder
import com.github.mayblock.easylib.base.impl.bukkit.overlay.slot.LiveSlot
import com.github.mayblock.easylib.base.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.base.impl.bukkit.util.stack
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class OverlaySlotMapTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun spec(block: OverlaySlotBuilder.() -> Unit = {}) =
        OverlaySlotBuilder().apply(block).build()

    @Test
    fun `get 返回声明槽的 LiveSlot，未声明返回 null`() {
        val map = SlotMap(mapOf(2 to spec {
            item(Material.STONE)
        }))
        assertEquals(Material.STONE, map[2]!!.item.type)
        assertNull(map[5])
    }

    @Test
    fun `forEachUpdatable 只遍历带 update rule 的槽`() {
        val map = SlotMap(
            mapOf(
                1 to spec(),
                2 to spec { onUpdate(TaskScheduler.Trigger.Once) { } },
            )
        )
        val visited = mutableListOf<Int>()
        map.forEachUpdatable { index, _ -> visited += index }
        assertEquals(listOf(2), visited)
    }

    @Test
    fun `LiveSlot item 可变且初值为 spec 物品`() {
        val s = LiveSlot(spec {
            item(Material.STONE)
        })
        assertEquals(Material.STONE, s.item.type)
        val diamond = stack(Material.DIAMOND)
        s.item = diamond
        assertEquals(diamond, s.item) // 写入内容生效
        assertNotSame(diamond, s.item) // 写时克隆：存的是副本，外部引用改不到内部
    }

    @Test
    fun `LiveSlot 持有 spec 物品的独立副本，构建后改原对象不波及内部`() {
        val template = stack(Material.STONE, 1)
        val s = LiveSlot(OverlaySlotBuilder().apply {
            item(template)
        }.build())
        template.amount = 99 // 外部（DSL 调用者）仍握着原对象并原地改
        assertEquals(1, s.item.amount) // 内部副本不受影响，缓存 key 稳定
    }
}
