package com.github.mayblock.easylib.base.impl.bukkit.item

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomItemDispatchTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    private lateinit var registry: CustomItemRegistryImpl

    @BeforeTest fun setUp() {
        server = MockBukkit.mock()
        registry = CustomItemRegistryImpl(MockBukkit.createMockPlugin("EasyLibTest"))
    }
    @AfterTest fun tearDown() = MockBukkit.unmock()

    private fun key(name: String) = NamespacedKey("easylibtest", name)

    private fun interact(player: org.bukkit.entity.Player): PlayerInteractEvent =
        PlayerInteractEvent(
            player, Action.RIGHT_CLICK_AIR,
            player.inventory.itemInMainHand, null, BlockFace.SELF, EquipmentSlot.HAND
        ).also { server.pluginManager.callEvent(it) }

    @Test fun `onInteract 只对匹配物品触发`() {
        var fired = 0
        val wand = registry.define(Material.STICK, key("wand")) { onInteract { fired++ } }
        val p = server.addPlayer()

        p.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(Material.STICK))
        interact(p)
        assertEquals(0, fired)   // 普通木棍不触发

        p.inventory.setItemInMainHand(wand.createStack())
        interact(p)
        assertEquals(1, fired)
    }

    @Test fun `consume 扣减手上物品且扣到 0 清空槽位`() {
        val wand = registry.define(Material.STICK, key("wand")) { onInteract { consume() } }
        val p = server.addPlayer()
        p.inventory.setItemInMainHand(wand.createStack(2))

        interact(p)
        assertEquals(1, p.inventory.itemInMainHand.amount)
        interact(p)
        assertFalse(wand.matches(p.inventory.itemInMainHand))   // 槽位已清空
    }

    @Test fun `创造模式 consume 不消耗`() {
        val wand = registry.define(Material.STICK, key("wand")) { onInteract { consume() } }
        val p = server.addPlayer()
        p.gameMode = GameMode.CREATIVE
        p.inventory.setItemInMainHand(wand.createStack(2))
        interact(p)
        assertEquals(2, p.inventory.itemInMainHand.amount)
    }

    @Test fun `cancel 取消底层事件`() {
        val wand = registry.define(Material.STICK, key("wand")) { onInteract { cancel() } }
        val p = server.addPlayer()
        p.inventory.setItemInMainHand(wand.createStack())
        val e = interact(p)
        assertTrue(e.isCancelled)
    }

    @Test fun `onInventoryClick 触发且 consume 作用于被点击槽位`() {
        var seen: CustomItemClick_seen? = null
        val wand = registry.define(Material.STICK, key("wand")) {
            onInventoryClick { seen = CustomItemClick_seen(item.key); consume() }
        }
        val p = server.addPlayer()
        val inv = server.createInventory(null, InventoryType.CHEST)
        inv.setItem(0, wand.createStack(2))
        val view = p.openInventory(inv)!!
        val e = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        server.pluginManager.callEvent(e)

        assertEquals(wand.key, seen?.key)
        assertEquals(1, inv.getItem(0)?.amount)
    }

    @Test fun `unregister 后回调停止触发`() {
        var fired = 0
        val wand = registry.define(Material.STICK, key("wand")) { onInteract { fired++ } }
        val p = server.addPlayer()
        p.inventory.setItemInMainHand(wand.createStack())
        registry.unregister(key("wand"))
        interact(p)
        assertEquals(0, fired)
    }

    @Test fun `shutdown 后监听器彻底注销`() {
        var fired = 0
        val wand = registry.define(Material.STICK, key("wand")) { onInteract { fired++ } }
        val p = server.addPlayer()
        val stack = wand.createStack()
        registry.shutdown()

        // shutdown 后重新定义也不该经由旧监听器触发
        p.inventory.setItemInMainHand(stack)
        interact(p)
        assertEquals(0, fired)
    }

    @Test fun `已取消的交互不分发`() {
        var fired = 0
        val wand = registry.define(Material.STICK, key("wand")) { onInteract { fired++ } }
        val p = server.addPlayer()
        p.inventory.setItemInMainHand(wand.createStack())
        val e = PlayerInteractEvent(
            p, Action.RIGHT_CLICK_AIR,
            p.inventory.itemInMainHand, null, BlockFace.SELF, EquipmentSlot.HAND
        )
        e.isCancelled = true
        server.pluginManager.callEvent(e)
        assertEquals(0, fired)
    }

    @Test fun `已取消的容器点击不分发`() {
        var fired = 0
        val wand = registry.define(Material.STICK, key("wand")) { onInventoryClick { fired++ } }
        val p = server.addPlayer()
        val inv = server.createInventory(null, InventoryType.CHEST)
        inv.setItem(0, wand.createStack())
        val view = p.openInventory(inv)!!
        val e = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        e.isCancelled = true
        server.pluginManager.callEvent(e)
        assertEquals(0, fired)
    }

    @Test fun `click consume 隐含取消底层事件`() {
        val wand = registry.define(Material.STICK, key("wand")) { onInventoryClick { consume() } }
        val p = server.addPlayer()
        val inv = server.createInventory(null, InventoryType.CHEST)
        inv.setItem(0, wand.createStack(2))
        val view = p.openInventory(inv)!!
        val e = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        server.pluginManager.callEvent(e)
        assertTrue(e.isCancelled)
        assertEquals(1, inv.getItem(0)?.amount)
    }

    private data class CustomItemClick_seen(val key: NamespacedKey)
}
