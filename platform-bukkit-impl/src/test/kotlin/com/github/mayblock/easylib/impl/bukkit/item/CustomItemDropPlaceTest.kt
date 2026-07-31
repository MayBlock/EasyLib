package com.github.mayblock.easylib.impl.bukkit.item

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
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

    private fun interactBlock(p: org.bukkit.entity.Player, stack: org.bukkit.inventory.ItemStack): PlayerInteractEvent =
        PlayerInteractEvent(
            p, Action.RIGHT_CLICK_BLOCK, stack,
            p.world.getBlockAt(0, 63, 0), BlockFace.UP, EquipmentSlot.HAND
        ).also { server.pluginManager.callEvent(it) }

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

    // ---- 放置身份备忘（interact 阶段手部被改写导致空快照时的兜底识别）----

    @Test fun `快照为空时凭同 tick 交互备忘执行默认禁`() {
        val stone = registry.define(Material.STONE, key("magic_stone"))
        val p = server.addPlayer()
        interactBlock(p, stone.createStack())
        val e = placeEvent(p, org.bukkit.inventory.ItemStack(Material.AIR))
        server.pluginManager.callEvent(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `快照为空时凭备忘分发 onBlockPlace 且 item 正确`() {
        var seenKey: NamespacedKey? = null
        registry.define(Material.STONE, key("magic_stone")) { onBlockPlace { seenKey = item.key } }
        val p = server.addPlayer()
        interactBlock(p, registry.get(key("magic_stone"))!!.createStack())
        val e = placeEvent(p, org.bukkit.inventory.ItemStack(Material.AIR))
        server.pluginManager.callEvent(e)
        assertEquals(key("magic_stone"), seenKey)
        assertFalse(e.isCancelled)
    }

    @Test fun `空快照且无备忘或异玩家时不分发`() {
        var fired = 0
        registry.define(Material.STONE, key("magic_stone")) { onBlockPlace { fired++ } }
        val a = server.addPlayer()
        val b = server.addPlayer()
        val e1 = placeEvent(a, org.bukkit.inventory.ItemStack(Material.AIR))
        server.pluginManager.callEvent(e1)
        assertEquals(0, fired)
        assertFalse(e1.isCancelled)
        interactBlock(a, registry.get(key("magic_stone"))!!.createStack())
        val e2 = placeEvent(b, org.bukkit.inventory.ItemStack(Material.AIR))
        server.pluginManager.callEvent(e2)
        assertEquals(0, fired)
        assertFalse(e2.isCancelled)
    }

    @Test fun `非放置手势的交互不记备忘`() {
        var fired = 0
        registry.define(Material.STONE, key("magic_stone")) { onBlockPlace { fired++ } }
        val p = server.addPlayer()
        // RIGHT_CLICK_AIR：不应记录放置备忘
        PlayerInteractEvent(
            p, Action.RIGHT_CLICK_AIR,
            registry.get(key("magic_stone"))!!.createStack(), null, BlockFace.SELF, EquipmentSlot.HAND
        ).also { server.pluginManager.callEvent(it) }
        val e = placeEvent(p, org.bukkit.inventory.ItemStack(Material.AIR))
        server.pluginManager.callEvent(e)
        assertEquals(0, fired)
        assertFalse(e.isCancelled)
    }

    @Test fun `备忘随 gameTime 前进而过期`() {
        registry.define(Material.STONE, key("magic_stone"))
        val p = server.addPlayer()
        interactBlock(p, registry.get(key("magic_stone"))!!.createStack())
        val world = p.world as org.mockbukkit.mockbukkit.world.WorldMock
        world.setGameTime(world.gameTime + 1)
        val e = placeEvent(p, org.bukkit.inventory.ItemStack(Material.AIR))
        server.pluginManager.callEvent(e)
        assertFalse(e.isCancelled)
    }

    @Test fun `非空快照不咨询备忘`() {
        var fired = 0
        registry.define(Material.STONE, key("magic_stone")) { onBlockPlace { fired++ } }
        val p = server.addPlayer()
        interactBlock(p, registry.get(key("magic_stone"))!!.createStack())
        val e = placeEvent(p, org.bukkit.inventory.ItemStack(Material.DIRT))
        server.pluginManager.callEvent(e)
        assertEquals(0, fired)
        assertFalse(e.isCancelled)
    }

    @Test fun `备忘命中即清（一次手势至多救援一次）`() {
        registry.define(Material.STONE, key("magic_stone"))
        val p = server.addPlayer()
        interactBlock(p, registry.get(key("magic_stone"))!!.createStack())
        val first = placeEvent(p, org.bukkit.inventory.ItemStack(Material.AIR))
        server.pluginManager.callEvent(first)
        assertTrue(first.isCancelled)   // 首次：备忘救援，默认禁生效
        val second = placeEvent(p, org.bukkit.inventory.ItemStack(Material.AIR))
        server.pluginManager.callEvent(second)
        assertFalse(second.isCancelled) // 二次：备忘已清，不再救援
    }
}
