package com.github.mayblock.easylib.impl.bukkit.item

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.EquipmentSlot
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomItemDropPlaceTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    private lateinit var registry: CustomItemRegistryImpl

    @BeforeTest fun setUp() {
        server = MockBukkit.mock()
        registry = CustomItemRegistryImpl(MockBukkit.createMockPlugin("EasyLibTest"))
    }
    @AfterTest fun tearDown() = MockBukkit.unmock()

    private fun key(name: String) = NamespacedKey("easylibtest", name)

    private fun dropEvent(p: org.bukkit.entity.Player, stack: org.bukkit.inventory.ItemStack): PlayerDropItemEvent {
        val entity = p.world.dropItem(p.location, stack)
        return PlayerDropItemEvent(p, entity)
    }

    private fun placeEvent(p: org.bukkit.entity.Player, stack: org.bukkit.inventory.ItemStack): BlockPlaceEvent {
        val placed = p.world.getBlockAt(0, 64, 0)
        val against = p.world.getBlockAt(0, 63, 0)
        return BlockPlaceEvent(placed, placed.state, against, stack, p, true, EquipmentSlot.HAND)
    }

    // ---- drop ----

    @Test fun `未注册 onDrop 时丢弃默认允许`() {
        val wand = registry.define(Material.STICK, key("wand"))
        val p = server.addPlayer()
        val e = dropEvent(p, wand.createStack())
        server.pluginManager.callEvent(e)
        assertFalse(e.isCancelled)
    }

    @Test fun `注册 onDrop 后分发且 cancel 生效`() {
        var seenKey: NamespacedKey? = null
        registry.define(Material.STICK, key("wand")) {
            onDrop { seenKey = item.key; cancel() }
        }
        val p = server.addPlayer()
        val e = dropEvent(p, registry.get(key("wand"))!!.createStack())
        server.pluginManager.callEvent(e)
        assertEquals(key("wand"), seenKey)
        assertTrue(e.isCancelled)
    }

    @Test fun `非自定义物品丢弃不分发`() {
        var fired = 0
        registry.define(Material.STICK, key("wand")) { onDrop { fired++ } }
        val p = server.addPlayer()
        val e = dropEvent(p, org.bukkit.inventory.ItemStack(Material.STICK))
        server.pluginManager.callEvent(e)
        assertEquals(0, fired)
        assertFalse(e.isCancelled)
    }

    @Test fun `预先取消的丢弃不分发`() {
        var fired = 0
        val wand = registry.define(Material.STICK, key("wand")) { onDrop { fired++ } }
        val p = server.addPlayer()
        val e = dropEvent(p, wand.createStack())
        e.isCancelled = true
        server.pluginManager.callEvent(e)
        assertEquals(0, fired)
    }

    // ---- block place ----

    @Test fun `未注册 onBlockPlace 时放置被自动取消`() {
        val stone = registry.define(Material.STONE, key("magic_stone"))
        val p = server.addPlayer()
        val e = placeEvent(p, stone.createStack())
        server.pluginManager.callEvent(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `注册 onBlockPlace 后接管：默认放行且 handler 可 cancel`() {
        var seenKey: NamespacedKey? = null
        registry.define(Material.STONE, key("magic_stone")) {
            onBlockPlace { seenKey = item.key }
        }
        val p = server.addPlayer()
        val allow = placeEvent(p, registry.get(key("magic_stone"))!!.createStack())
        server.pluginManager.callEvent(allow)
        assertEquals(key("magic_stone"), seenKey)
        assertFalse(allow.isCancelled)

        registry.unregister(key("magic_stone"))
        registry.define(Material.STONE, key("deny_stone")) {
            onBlockPlace { cancel() }
        }
        val deny = placeEvent(p, registry.get(key("deny_stone"))!!.createStack())
        server.pluginManager.callEvent(deny)
        assertTrue(deny.isCancelled)
    }

    @Test fun `非自定义物品放置不受影响`() {
        registry.define(Material.STONE, key("magic_stone"))
        val p = server.addPlayer()
        val e = placeEvent(p, org.bukkit.inventory.ItemStack(Material.STONE))
        server.pluginManager.callEvent(e)
        assertFalse(e.isCancelled)
    }

    @Test fun `预先取消的放置不分发`() {
        var fired = 0
        registry.define(Material.STONE, key("magic_stone")) { onBlockPlace { fired++ } }
        val p = server.addPlayer()
        val e = placeEvent(p, registry.get(key("magic_stone"))!!.createStack())
        e.isCancelled = true
        server.pluginManager.callEvent(e)
        assertEquals(0, fired)
        assertTrue(e.isCancelled)
    }
}
