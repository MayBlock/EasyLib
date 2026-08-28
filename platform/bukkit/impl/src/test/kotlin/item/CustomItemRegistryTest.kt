package com.github.mayblock.easylib.platform.bukkit.impl.item

import com.github.mayblock.easylib.platform.bukkit.api.item.meta
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class CustomItemRegistryTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    private lateinit var plugin: org.bukkit.plugin.Plugin
    private lateinit var registry: CustomItemRegistryImpl

    @BeforeTest fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin("EasyLibTest")   // 只创建一次，重启模拟测试复用同一插件实例
        registry = CustomItemRegistryImpl(plugin)
    }
    @AfterTest fun tearDown() = MockBukkit.unmock()

    private fun key(name: String) = NamespacedKey("easylibtest", name)

    @Test fun `define 返回可用的 CustomItem 且 get 与 fromStack 均可反查`() {
        val item = registry.define(Material.STICK, key("wand")) {
            meta {
                setDisplayName("Wand")
            }
        }
        assertEquals(Material.STICK, item.type)
        assertSame(item, registry.get(key("wand")))
        val stack = item.createStack(3)
        assertEquals(3, stack.amount)
        assertEquals("Wand", stack.itemMeta?.displayName)
        assertSame(item, registry.fromStack(stack))
        assertTrue(item.matches(stack))
    }

    @Test fun `matches 与 fromStack 不认普通物品`() {
        val item = registry.define(Material.STICK, key("wand"))
        val plain = org.bukkit.inventory.ItemStack(Material.STICK)
        assertFalse(item.matches(plain))
        assertNull(registry.fromStack(plain))
    }

    @Test fun `重复 define 同一 key 抛异常且不覆盖`() {
        val first = registry.define(Material.STICK, key("wand"))
        assertFailsWith<IllegalArgumentException> { registry.define(Material.PAPER, key("wand")) }
        assertSame(first, registry.get(key("wand")))
    }

    @Test fun `unregister 与 isRegistered`() {
        registry.define(Material.STICK, key("wand"))
        assertTrue(registry.isRegistered(key("wand")))
        assertTrue(registry.unregister(key("wand")))
        assertFalse(registry.isRegistered(key("wand")))
        assertFalse(registry.unregister(key("wand")))
    }

    @Test fun `身份跨注册表实例稳定 —— 模拟重启后旧物品仍被识别`() {
        val old = registry.define(Material.STICK, key("wand"))
        val survivedStack = old.createStack()   // 「重启前」发出的物品
        registry.shutdown()

        val rebooted = CustomItemRegistryImpl(plugin)   // 「重启后」的新注册表
        val redefined = rebooted.define(Material.STICK, key("wand"))
        assertSame(redefined, rebooted.fromStack(survivedStack))
        rebooted.shutdown()
    }

    @Test fun `take 非正数 amount 抛异常且不改动背包`() {
        val item = registry.define(Material.STICK, key("wand"))
        val p = server.addPlayer()
        item.give(p, 2)
        assertFailsWith<IllegalArgumentException> { item.take(p, 0) }
        assertFailsWith<IllegalArgumentException> { item.take(p, -5) }
        assertEquals(2, p.inventory.contents.filterNotNull().filter(item::matches).sumOf { it.amount })
    }

    @Test fun `take 部分扣减持久化到背包`() {
        val item = registry.define(Material.STICK, key("wand"))
        val p = server.addPlayer()
        item.give(p, 5)
        assertTrue(item.take(p, 2))
        assertEquals(3, p.inventory.contents.filterNotNull().filter(item::matches).sumOf { it.amount })
    }
}
