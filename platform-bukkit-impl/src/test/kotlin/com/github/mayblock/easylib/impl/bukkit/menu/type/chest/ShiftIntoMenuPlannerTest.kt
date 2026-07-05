package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShiftIntoMenuPlannerTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun stack(m: Material, n: Int) = ItemStack(m, n)

    @Test fun `优先填同类未满堆叠，再填空槽`() {
        val source = stack(Material.STONE, 40)
        val slots = listOf(
            1 to stack(Material.STONE, 60), // 同类，剩 4
            3 to null,                       // 空
            5 to stack(Material.DIRT, 1),    // 异类，跳过
        )
        val plan = ShiftIntoMenuPlanner.plan(source, slots)
        // 先向 slot1 放 4（填满 64），余 36 放入空 slot3
        assertEquals(listOf(Placement(1, 4), Placement(3, 36)), plan)
    }

    @Test fun `空间不足时只分发能放下的量`() {
        val source = stack(Material.STONE, 100)
        val slots = listOf(1 to null) // 一个空槽最多 64
        assertEquals(listOf(Placement(1, 64)), ShiftIntoMenuPlanner.plan(source, slots))
    }

    @Test fun `无可放置空间返回空计划`() {
        val source = stack(Material.STONE, 10)
        val slots = listOf(
            1 to stack(Material.DIRT, 1),     // 异类
            3 to stack(Material.STONE, 64),   // 同类但已满
        )
        assertEquals(emptyList(), ShiftIntoMenuPlanner.plan(source, slots))
    }

    @Test fun `按 slot 顺序分发`() {
        val source = stack(Material.STONE, 5)
        val slots = listOf(7 to null, 2 to null) // 传入顺序即分发顺序
        assertEquals(listOf(Placement(7, 5)), ShiftIntoMenuPlanner.plan(source, slots))
    }
}
