package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChestClickLogicTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private val menuSize = 27
    private fun logic(hide: Boolean = false, hasPlaceable: Boolean = true) =
        ChestClickLogic(menuSize, hide, hasPlaceable)

    private fun view(item: ItemStack?, movable: Boolean = false, placeable: Boolean = false) =
        SlotView(item, movable, placeable)

    private fun menuCursor(item: ItemStack, origin: Int) = VirtualCursor(item, CursorOrigin.MenuSlot(origin))
    private fun invCursor(item: ItemStack, ws: Int) = VirtualCursor(item, CursorOrigin.PlayerInventory(ws))

    // ── 空光标 · 菜单区 ────────────────────────────────────────────

    @Test
    fun `左键 movable 槽位拿起全部`() {
        val d = logic().decide(5, false, null, view(ItemStack(Material.STONE, 5), movable = true), null)
        assertIs<ClickDecision.PickupFromMenu>(d)
        assertEquals(5, d.slot); assertEquals(5, d.amount)
    }

    @Test
    fun `右键 movable 槽位拿起向上取整的一半`() {
        val d = logic().decide(5, true, null, view(ItemStack(Material.STONE, 5), movable = true), null)
        assertIs<ClickDecision.PickupFromMenu>(d)
        assertEquals(3, d.amount)
    }

    @Test
    fun `非 movable、空物品、未声明槽位一律拒绝`() {
        assertIs<ClickDecision.Deny>(logic().decide(5, false, null, view(ItemStack(Material.STONE), movable = false), null))
        assertIs<ClickDecision.Deny>(logic().decide(5, false, null, view(ItemStack(Material.AIR), movable = true), null))
        assertIs<ClickDecision.Deny>(logic().decide(5, false, null, null, null))
    }

    // ── 空光标 · 背包区 ────────────────────────────────────────────

    @Test
    fun `背包区拿起需要 hide=false、存在 placeable 槽且槽位有物品`() {
        assertIs<ClickDecision.PickupFromInventory>(logic().decide(30, false, null, null, ItemStack(Material.EMERALD)))
        assertIs<ClickDecision.Deny>(logic(hide = true, hasPlaceable = false).decide(30, false, null, null, ItemStack(Material.EMERALD)))
        assertIs<ClickDecision.Deny>(logic(hasPlaceable = false).decide(30, false, null, null, ItemStack(Material.EMERALD)))
        assertIs<ClickDecision.Deny>(logic().decide(30, false, null, null, null))
    }

    // ── 菜单源光标 · 菜单区 ─────────────────────────────────────────

    @Test
    fun `菜单源光标放入空 placeable 槽位（左键全放、右键放一）`() {
        val cursor = menuCursor(ItemStack(Material.STONE, 4), origin = 2)
        val left = logic().decide(5, false, cursor, view(null, placeable = true), null)
        assertIs<ClickDecision.PlaceInMenu>(left)
        assertEquals(4, left.amount); assertTrue(!left.fromInventory)
        val right = logic().decide(5, true, cursor, view(null, placeable = true), null)
        assertIs<ClickDecision.PlaceInMenu>(right)
        assertEquals(1, right.amount)
    }

    @Test
    fun `放回来源槽位不要求 placeable`() {
        val cursor = menuCursor(ItemStack(Material.STONE, 4), origin = 5)
        val d = logic().decide(5, false, cursor, view(null, movable = true, placeable = false), null)
        assertIs<ClickDecision.PlaceInMenu>(d)
    }

    @Test
    fun `非 placeable 且非来源槽位拒绝放置`() {
        val cursor = menuCursor(ItemStack(Material.STONE, 4), origin = 2)
        assertIs<ClickDecision.Deny>(logic().decide(5, false, cursor, view(null, placeable = false), null))
    }

    @Test
    fun `同类堆叠受 maxStackSize 限制，满栈拒绝`() {
        val cursor = menuCursor(ItemStack(Material.STONE, 10), origin = 2)
        val d = logic().decide(5, false, cursor, view(ItemStack(Material.STONE, 60), placeable = true), null)
        assertIs<ClickDecision.PlaceInMenu>(d)
        assertEquals(4, d.amount) // 64 - 60
        assertIs<ClickDecision.Deny>(
            logic().decide(5, false, cursor, view(ItemStack(Material.STONE, 64), placeable = true), null)
        )
    }

    @Test
    fun `异类交换需要目标同时 movable 与 placeable，且真实源交换一律拒绝`() {
        val menuOrigin = menuCursor(ItemStack(Material.STONE, 1), origin = 2)
        val target = view(ItemStack(Material.DIRT, 1), movable = true, placeable = true)
        assertIs<ClickDecision.SwapWithMenu>(logic().decide(5, false, menuOrigin, target, null))
        assertIs<ClickDecision.Deny>(
            logic().decide(5, false, menuOrigin, view(ItemStack(Material.DIRT, 1), movable = true, placeable = false), null)
        )
        val invOrigin = invCursor(ItemStack(Material.STONE, 1), ws = 30)
        assertIs<ClickDecision.Deny>(logic().decide(5, false, invOrigin, target, null))
    }

    // ── 背包源光标 · 菜单区 ─────────────────────────────────────────

    @Test
    fun `背包源光标放入空 placeable 槽位标记 fromInventory`() {
        val d = logic().decide(5, false, invCursor(ItemStack(Material.EMERALD, 2), 30), view(null, placeable = true), null)
        assertIs<ClickDecision.PlaceInMenu>(d)
        assertTrue(d.fromInventory)
    }

    @Test
    fun `背包源光标放入非 placeable 槽位拒绝（isPutBack 对背包源永不生效）`() {
        val d = logic().decide(5, false, invCursor(ItemStack(Material.EMERALD, 2), 30), view(null, placeable = false), null)
        assertIs<ClickDecision.Deny>(d)
    }

    @Test
    fun `右键拿起单个物品仍拿起 1 个`() {
        val d = logic().decide(5, true, null, view(ItemStack(Material.STONE, 1), movable = true), null)
        assertIs<ClickDecision.PickupFromMenu>(d)
        assertEquals(1, d.amount)
    }

    @Test
    fun `放回来源槽位但槽内已是异类物品时不享受放回豁免`() {
        val cursor = menuCursor(ItemStack(Material.STONE, 2), origin = 5)
        val d = logic().decide(5, false, cursor, view(ItemStack(Material.DIRT, 1), movable = true, placeable = false), null)
        assertIs<ClickDecision.Deny>(d)
    }

    @Test
    fun `右键从背包区拿起同样允许`() {
        val d = logic().decide(30, true, null, null, ItemStack(Material.EMERALD))
        assertIs<ClickDecision.PickupFromInventory>(d)
    }

    // ── 光标 · 背包区 ──────────────────────────────────────────────

    @Test
    fun `菜单源光标点背包区任意槽位为取出`() {
        val d = logic().decide(30, false, menuCursor(ItemStack(Material.STONE, 1), 2), null, null)
        assertIs<ClickDecision.DropToInventory>(d)
        assertEquals(30, d.windowSlot)
    }

    @Test
    fun `背包源光标仅可放回原槽位，其它拒绝`() {
        val cursor = invCursor(ItemStack(Material.EMERALD, 1), ws = 30)
        assertIs<ClickDecision.PutBackToInventory>(logic().decide(30, false, cursor, null, null))
        assertIs<ClickDecision.Deny>(logic().decide(31, false, cursor, null, null))
    }

    // ── 辅助函数 ──────────────────────────────────────────────────

    @Test
    fun `playerInventoryWindowSlots 覆盖 36 个槽且不含菜单区`() {
        assertEquals(27..62, playerInventoryWindowSlots(27))
    }

    @Test
    fun `chestWindowSlotToBukkit 主背包与热键栏换算`() {
        assertEquals(9, chestWindowSlotToBukkit(27, 27))
        assertEquals(35, chestWindowSlotToBukkit(53, 27))
        assertEquals(0, chestWindowSlotToBukkit(54, 27))
        assertEquals(8, chestWindowSlotToBukkit(62, 27))
    }

    @Test
    fun `requirePlaceableVisible 在 hide 且存在 placeable 时报错`() {
        val placeableSpec = SlotBuilder(InventoryClickEvent::class.java)
            .build(ItemStack(Material.AIR), placeable = true)
        val plainSpec = SlotBuilder(InventoryClickEvent::class.java).build(ItemStack(Material.STONE))
        assertFailsWith<IllegalArgumentException> {
            requirePlaceableVisible(hidePlayerInventory = true, specs = mapOf(0 to placeableSpec))
        }
        requirePlaceableVisible(hidePlayerInventory = true, specs = mapOf(0 to plainSpec))
        requirePlaceableVisible(hidePlayerInventory = false, specs = mapOf(0 to placeableSpec))
    }
}
