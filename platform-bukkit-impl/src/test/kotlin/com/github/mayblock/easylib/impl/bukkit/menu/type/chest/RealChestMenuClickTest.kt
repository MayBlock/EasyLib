package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.menu.MenuInteractionListener
import com.github.mayblock.easylib.impl.bukkit.menu.MenuManager
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.scheduler.BukkitTaskScheduler
import com.github.mayblock.easylib.impl.bukkit.util.item
import com.github.mayblock.easylib.packetevents.PacketManager
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent as ApiInventoryClickEvent
import org.bukkit.event.inventory.InventoryClickEvent as BukkitInventoryClickEvent

class RealChestMenuClickTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    /**
     * 声明一个槽位。取出/放入不再是 movable/placeable 静态标志，而是事件契约：
     * [SlotTakeEvent]/[SlotPlaceEvent] 默认 `isCancelled = true`，block 内须显式放行。
     * 槽位只要出现在 specs（即被声明），门控就一律派发事件——放行与否完全由 block 决定。
     */
    private fun spec(item: ItemStack, block: (SlotScope<ApiInventoryClickEvent>.() -> Unit)? = null) =
        SlotBuilder(ApiInventoryClickEvent::class.java).apply { block?.invoke(this) }.build(item)

    private fun menu(specs: Map<Int, SlotSpec>): RealChestMenu =
        RealChestMenu(
            mockk<BukkitTaskScheduler>(relaxed = true),
            mockk<PacketManager<*>>(relaxed = true),
            Component.text("t"),
            ChestMenuType.GENERIC_9X3,
            specs,
            hidePlayerInventory = false
        )

    private fun open(m: RealChestMenu): Pair<Player, InventoryView> {
        val p = server.addPlayer()
        return p to p.openInventory(m.inventory)!!
    }

    private fun click(view: InventoryView, rawSlot: Int, action: InventoryAction, click: ClickType = ClickType.LEFT): BukkitInventoryClickEvent =
        BukkitInventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, click, action)

    @Test fun `不可变槽点击被取消`() {
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND))))
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
    }

    // ---- take：两态断言（无 handler → 拒绝 / 显式放行 → 放行）----

    @Test fun `声明 onTake 但不放行则拒绝（默认取消）`() {
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 2)) { onTake { /* 不设置 isCancelled，保持默认 true */ } }))
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `onTake 显式放行则触发 SlotTakeEvent 且不取消`() {
        var take = 0
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 2)) { onTake { isCancelled = false; take++ } }))
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertEquals(1, take)
        assertFalse(e.isCancelled)
    }

    @Test fun `未声明的槽不派发事件、一律取消`() {
        var take = 0
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 2)) { onTake { isCancelled = false; take++ } }))
        m.on { on<SlotTakeEvent> { take++ } } // 全局订阅也不会补救未声明槽
        val (_, view) = open(m)
        val e = click(view, 6, InventoryAction.PICKUP_ALL) // 槽6 未声明
        m.handleClick(e)
        assertEquals(0, take)
        assertTrue(e.isCancelled)
    }

    // ---- place：两态断言 ----

    @Test fun `声明 onPlace 但不放行则拒绝（默认取消）`() {
        val m = menu(mapOf(5 to spec(item(Material.AIR)) { onPlace { } }))
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 1))
        val e = click(view, 5, InventoryAction.PLACE_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `onPlace 显式放行则触发 SlotPlaceEvent 且不取消`() {
        var place = 0
        val m = menu(mapOf(5 to spec(item(Material.AIR)) { onPlace { isCancelled = false; place++ } }))
        val (p, view) = open(m)
        view.setCursor(item(Material.EMERALD, 1))
        val e = click(view, 5, InventoryAction.PLACE_ALL)
        m.handleClick(e)
        assertEquals(1, place)
        assertFalse(e.isCancelled)
    }

    @Test fun `信息性 onClick 对已声明槽触发`() {
        var clicks = 0
        val built =
            SlotBuilder(ApiInventoryClickEvent::class.java).apply { onClick { clicks++ } }.build(item(Material.BARRIER))
        val m = menu(mapOf(8 to built))
        val (_, view) = open(m)
        m.handleClick(click(view, 8, InventoryAction.PICKUP_ALL))
        assertEquals(1, clicks)
    }

    @Test fun `shift 入菜单只向声明了 onPlace 的候选槽分发`() {
        val places = mutableListOf<Int>()
        val m = menu(mapOf(
            0 to spec(item(Material.STONE, 60)) { onPlace { isCancelled = false } }, // 同类剩 4，放行
            1 to spec(item(Material.DIAMOND)),                                       // 已声明但无 onPlace：不参与候选
            2 to spec(item(Material.AIR)) { onPlace { isCancelled = false } },        // 空，放行
        ))
        m.on { on<SlotPlaceEvent> { places += index } }
        val (p, view) = open(m)
        p.inventory.setItem(0, item(Material.STONE, 40)) // 底部第一格
        val rawBottom = m.inventory.size + 0 // 底部第一格的 rawSlot
        val e = click(view, rawBottom, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        m.handleClick(e)
        assertTrue(e.isCancelled) // 原生被取消，改手动分发
        assertEquals(listOf(0, 2), places) // 先填同类槽0（+4），再填空槽2（+36）
        assertEquals(64, m.inventory.getItem(0)!!.amount)
        assertEquals(36, m.inventory.getItem(2)!!.amount)
        assertEquals(Material.DIAMOND, m.inventory.getItem(1)!!.type) // 槽1（无 onPlace）未被污染
    }

    @Test fun `拖拽触及未声明顶部槽则整体取消`() {
        val m = menu(mapOf(0 to spec(item(Material.AIR)) { onPlace { isCancelled = false } })) // 只声明槽0，槽1 完全未声明
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 2))
        val newItems = mapOf(0 to item(Material.EMERALD, 1), 1 to item(Material.EMERALD, 1))
        val e = InventoryDragEvent(view, item(Material.AIR), item(Material.EMERALD, 2), false, newItems)
        m.handleDrag(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `拖拽触及已声明但未放行的顶部槽仍整体取消（事件契约默认拒绝）`() {
        val m = menu(mapOf(
            0 to spec(item(Material.AIR)) { onPlace { isCancelled = false } },
            1 to spec(item(Material.AIR)) { onPlace { } }, // 声明但不放行
        ))
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 2))
        val newItems = mapOf(0 to item(Material.EMERALD, 1), 1 to item(Material.EMERALD, 1))
        val e = InventoryDragEvent(view, item(Material.AIR), item(Material.EMERALD, 2), false, newItems)
        m.handleDrag(e)
        assertTrue(e.isCancelled) // 原生拖拽只能整体取消
    }

    @Test fun `拖拽仅触及已放行的顶部槽则放行并逐槽 onPlace`() {
        var place = 0
        val m = menu(
            mapOf(
                0 to spec(item(Material.AIR)) { onPlace { isCancelled = false; place++ } },
                1 to spec(item(Material.AIR)) { onPlace { isCancelled = false; place++ } },
            )
        )
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 2))
        val newItems = mapOf(0 to item(Material.EMERALD, 1), 1 to item(Material.EMERALD, 1))
        val e = InventoryDragEvent(view, item(Material.AIR), item(Material.EMERALD, 2), false, newItems)
        m.handleDrag(e)
        assertFalse(e.isCancelled)
        assertEquals(2, place)
    }

    @Test fun `FireSwap 均显式放行则触发 take 与 place 且不取消`() {
        var take = 0; var place = 0
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 1)) {
            onTake { isCancelled = false; take++ }
            onPlace { isCancelled = false; place++ }
        }))
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 1))
        val e = click(view, 5, InventoryAction.SWAP_WITH_CURSOR)
        m.handleClick(e)
        assertEquals(1, take); assertEquals(1, place)
        assertFalse(e.isCancelled)
    }

    @Test fun `FireSwap 中 take 未放行则取消 Bukkit 事件且不触发 place`() {
        var place = 0
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 1)) {
            onTake { /* 不放行，保持默认取消 */ }
            onPlace { isCancelled = false; place++ }
        }))
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 1))
        val e = click(view, 5, InventoryAction.SWAP_WITH_CURSOR)
        m.handleClick(e)
        assertTrue(e.isCancelled)
        assertEquals(0, place) // take 取消即短路，place 从未派发
    }

    @Test fun `shift 分发中某槽未放行则跳过、扣除只计实际放入`() {
        val m = menu(mapOf(
            0 to spec(item(Material.AIR)) { onPlace { /* 不放行 */ } },
            1 to spec(item(Material.AIR)) { onPlace { isCancelled = false } },
        ))
        val (p, view) = open(m)
        p.inventory.setItem(0, item(Material.STONE, 100))
        val e = click(view, m.inventory.size + 0, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        m.handleClick(e)
        assertNull(m.inventory.getItem(0))               // slot0 未放行 → 未放入
        assertEquals(36, m.inventory.getItem(1)!!.amount) // slot1 得 36（100 = 64+36，slot0 被跳过）
        assertEquals(64, e.currentItem!!.amount)                // 来源只扣实际放入的 36，剩 64
    }

    @Test fun `MenuInteractionListener 按 holder 路由点击到菜单`() {
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND)))) // 无 handler 的声明槽
        // 监听器路由前会校验菜单归属（防止多 MenuManager 实例重复处理），
        // 因此这里显式把 m 挂到一个 manager 名下，再用同一 manager 构造监听器。
        val mgr = MenuManager(
            mockk<BukkitTaskScheduler>(relaxed = true),
            mockk<PacketManager<*>>(relaxed = true),
            MockBukkit.createMockPlugin()
        )
        m.owner = mgr
        val (_, view) = open(m)
        val listener = MenuInteractionListener(mgr)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        listener.onInvClick(e)
        assertTrue(e.isCancelled) // 经 holder 路由到 handleClick，无 handler 的声明槽被取消
    }

    // ---- 新增专项用例（slot 交互契约重设计）----

    // ① 声明 onTake 但不放行 → 拒绝，证明默认取消（与「onTake 显式放行则触发...」构成两态对照）
    @Test fun `专项① 声明 onTake 不调用放行则始终拒绝`() {
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 1)) { onTake { /* no-op */ } }))
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
    }

    // ② 条件放行：handler 内按物品数量动态决定是否放行
    @Test fun `专项② onTake 按物品数量条件放行`() {
        val allowMenu = menu(mapOf(5 to spec(item(Material.DIAMOND, 2)) {
            onTake { isCancelled = item.amount < 2 }
        }))
        val (_, allowView) = open(allowMenu)
        val allowEvent = click(allowView, 5, InventoryAction.PICKUP_ALL)
        allowMenu.handleClick(allowEvent)
        assertFalse(allowEvent.isCancelled, "数量满足条件应放行")

        val denyMenu = menu(mapOf(5 to spec(item(Material.DIAMOND, 1)) {
            onTake { isCancelled = item.amount < 2 }
        }))
        val (_, denyView) = open(denyMenu)
        val denyEvent = click(denyView, 5, InventoryAction.PICKUP_ALL)
        denyMenu.handleClick(denyEvent)
        assertTrue(denyEvent.isCancelled, "数量不满足条件应拒绝")
    }

    // ③ 多 handler priority 覆盖：低优先级放行、高优先级再取消 → 最终取消（后执行者覆盖先执行者）
    @Test fun `专项③ 高优先级 handler 覆盖低优先级的放行决定`() {
        val order = mutableListOf<String>()
        val m = menu(mapOf(5 to spec(item(Material.DIAMOND, 1)) {
            onTake(Priority(20)) { isCancelled = true; order += "high-deny" }  // 后执行：再次取消
            onTake(Priority(1)) { isCancelled = false; order += "low-allow" } // 先执行：先放行
        }))
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertEquals(listOf("low-allow", "high-deny"), order) // 按 priority 升序执行
        assertTrue(e.isCancelled, "高优先级 handler 的取消决定应覆盖低优先级的放行")
    }

    // ④ 外部总线订阅者放行 cursor-place，证明总线（非仅 DSL onPlace）参与门控
    @Test fun `专项④ 外部总线订阅者可放行 cursor-place`() {
        var externalHandled = 0
        // 声明槽但不使用 DSL onPlace：门控只看是否声明（specs.containsKey），不看是否有 handler。
        val m = menu(mapOf(5 to spec(item(Material.AIR))))
        m.on { on<SlotPlaceEvent> { isCancelled = false; externalHandled++ } }
        val (_, view) = open(m)
        view.setCursor(item(Material.EMERALD, 1))
        val e = click(view, 5, InventoryAction.PLACE_ALL)
        m.handleClick(e)
        assertEquals(1, externalHandled)
        assertFalse(e.isCancelled, "外部总线订阅者的放行应生效")
    }
}
