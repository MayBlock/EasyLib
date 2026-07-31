package com.github.mayblock.easylib.impl.bukkit.item

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
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

    // ---- block place：本库自定义物品不可被放置 ----

    @Test fun `自定义物品的放置事件一律被取消（纵深防御）`() {
        val stone = registry.define(Material.STONE, key("magic_stone"))
        val p = server.addPlayer()
        val e = placeEvent(p, stone.createStack())
        server.pluginManager.callEvent(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `非自定义物品放置不受影响`() {
        registry.define(Material.STONE, key("magic_stone"))
        val p = server.addPlayer()
        val e = placeEvent(p, org.bukkit.inventory.ItemStack(Material.STONE))
        server.pluginManager.callEvent(e)
        assertFalse(e.isCancelled)
    }

    // ---- 放置禁止：interact 阶段拒绝物品使用 ----

    @Test fun `可放置材质的自定义物品交互阶段即拒绝物品使用`() {
        val stone = registry.define(Material.STONE, key("magic_stone"))
        val p = server.addPlayer()
        val e = interactBlock(p, stone.createStack())
        assertEquals(Event.Result.DENY, e.useItemInHand())   // 原版放置不会启动
    }

    @Test fun `非方块材质的自定义物品交互不拒绝物品使用`() {
        registry.define(Material.STICK, key("wand"))
        val p = server.addPlayer()
        val e = interactBlock(p, registry.get(key("wand"))!!.createStack())
        assertEquals(Event.Result.DEFAULT, e.useItemInHand())   // 非方块材质与放置无关，不拦
    }
}
