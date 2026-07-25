package com.github.mayblock.easylib.impl.bukkit.util

import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.*

class SlotDisplayMapTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private val a: UUID = UUID.randomUUID()
    private val b: UUID = UUID.randomUUID()

    @Test fun `commit 存入假显示并返回脏，重复提交同值不脏`() {
        val map = SlotDisplayMap()
        val base = stack(Material.EMERALD, 3)
        val display = stack(Material.EMERALD, 3) { setDisplayName("§a已存入") }
        assertTrue(map.commit(a, 4, base, display))
        assertEquals(display, map.lookup(a, 4)!!.bukkitItem)
        assertFalse(map.commit(a, 4, base, display.clone())) // 同值 → 不脏
    }

    @Test fun `commit 结果与基底相同则清除条目（透传真实）`() {
        val map = SlotDisplayMap()
        val base = stack(Material.EMERALD, 3)
        map.commit(a, 4, base, stack(Material.EMERALD, 3) { setDisplayName("x") })
        // 规则本轮无修改：display == base → 条目清除，且因视图从假变真而脏
        assertTrue(map.commit(a, 4, base, base.clone()))
        assertNull(map.lookup(a, 4))
        // 再提交一次同基底 → 无条目无变化 → 不脏
        assertFalse(map.commit(a, 4, base, base.clone()))
    }

    @Test fun `双 viewer 缓存隔离`() {
        val map = SlotDisplayMap()
        val base = stack(Material.PAPER)
        map.commit(a, 0, base, stack(Material.PAPER) { setDisplayName("A 的") })
        map.commit(b, 0, base, stack(Material.PAPER) { setDisplayName("B 的") })
        assertNotEquals(map.lookup(a, 0)!!.bukkitItem, map.lookup(b, 0)!!.bukkitItem)
    }

    @Test fun `invalidate 清全 viewer 的该槽，remove 清整个 viewer`() {
        val map = SlotDisplayMap()
        val base = stack(Material.PAPER)
        val d = stack(Material.PAPER) { setDisplayName("x") }
        map.commit(a, 0, base, d); map.commit(b, 0, base, d); map.commit(a, 1, base, d)
        map.invalidate(0)
        assertNull(map.lookup(a, 0)); assertNull(map.lookup(b, 0))
        assertNotNull(map.lookup(a, 1))
        map.remove(a)
        assertNull(map.lookup(a, 1))
    }

    @Test fun `存入为副本，事后改动外部对象不波及缓存`() {
        val map = SlotDisplayMap()
        val display = stack(Material.PAPER, 1) { setDisplayName("x") }
        map.commit(a, 0, stack(Material.PAPER), display)
        display.amount = 64
        assertEquals(1, map.lookup(a, 0)!!.bukkitItem.amount)
    }
}
