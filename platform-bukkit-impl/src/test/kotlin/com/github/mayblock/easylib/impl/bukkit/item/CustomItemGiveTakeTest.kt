package com.github.mayblock.easylib.impl.bukkit.item

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomItemGiveTakeTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    private lateinit var registry: CustomItemRegistryImpl

    @BeforeTest fun setUp() {
        server = MockBukkit.mock()
        registry = CustomItemRegistryImpl(MockBukkit.createMockPlugin("EasyLibTest"))
    }
    @AfterTest fun tearDown() = MockBukkit.unmock()

    private fun item() = registry.define(Material.STICK, NamespacedKey("easylibtest", "wand"))

    @Test fun `give 将带身份的物品放入背包`() {
        val wand = item()
        val p = server.addPlayer()
        wand.give(p, 2)
        val inBag = p.inventory.contents.filterNotNull().filter { wand.matches(it) }
        assertEquals(2, inBag.sumOf { it.amount })
    }

    @Test fun `take 足量时扣除并返回 true`() {
        val wand = item()
        val p = server.addPlayer()
        wand.give(p, 5)
        assertTrue(wand.take(p, 3))
        assertEquals(2, p.inventory.contents.filterNotNull().filter { wand.matches(it) }.sumOf { it.amount })
    }

    @Test fun `take 不足量时不扣除并返回 false`() {
        val wand = item()
        val p = server.addPlayer()
        wand.give(p, 2)
        assertFalse(wand.take(p, 3))
        assertEquals(2, p.inventory.contents.filterNotNull().filter { wand.matches(it) }.sumOf { it.amount })
    }

    @Test fun `take 跨多个堆叠扣除`() {
        val wand = item()
        val p = server.addPlayer()
        // 两次 give 产生两个独立堆叠（STICK 上限 64，此处刻意分两次放）
        wand.give(p, 1)
        p.inventory.addItem(wand.createStack(1))
        assertTrue(wand.take(p, 2))
        assertEquals(0, p.inventory.contents.filterNotNull().filter { wand.matches(it) }.sumOf { it.amount })
    }

    @Test fun `take 不误伤外观相同的普通物品`() {
        val wand = item()
        val p = server.addPlayer()
        p.inventory.addItem(org.bukkit.inventory.ItemStack(Material.STICK, 10))
        wand.give(p, 1)
        assertTrue(wand.take(p, 1))
        assertEquals(10, p.inventory.contents.filterNotNull()
            .filter { it.type == Material.STICK && !wand.matches(it) }.sumOf { it.amount })
    }
}
