package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.SlotGrid
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 立即在当前线程执行 Once 任务的假调度器：让 submit → process 同步化，便于断言。 */
private class InlineScheduler : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int { task.onTick(); return 0 }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

/** 录制型渲染器：记录调用而不发包。 */
private class RecordingRenderer : ChestClickRenderer {
    val repaints = mutableListOf<Int>()
    val cursors = mutableListOf<ItemStack?>()
    val emptiedWindowSlots = mutableListOf<Int>()
    val resyncedSlots = mutableListOf<Collection<Int>>()
    val bottomResyncs = mutableListOf<Int>()
    var inventoryUpdates = 0
    override fun repaintSlot(index: Int) { repaints += index }
    override fun sendCursor(player: Player, item: ItemStack?) { cursors += item }
    override fun sendWindowSlotEmpty(player: Player, windowSlot: Int) { emptiedWindowSlots += windowSlot }
    override fun resyncSlots(player: Player, slots: Collection<Int>) { resyncedSlots += slots }
    override fun resyncBottomAfterTransfer(player: Player, clickedWindowSlot: Int) { bottomResyncs += clickedWindowSlot }
    override fun updatePlayerInventory(player: Player) { inventoryUpdates++ }
}

class ChestClickEngineTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private val menuSize = 27
    private lateinit var renderer: RecordingRenderer
    private lateinit var events: MutableList<MenuEvent>
    private var cancelPlace = false
    private var cancelTake = false

    private fun spec(item: ItemStack, movable: Boolean = false, placeable: Boolean = false): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java).build(item, movable, placeable)

    private fun engine(
        specs: Map<Int, SlotSpec>,
        hide: Boolean = false,
    ): Pair<ChestClickEngine, SlotGrid> {
        renderer = RecordingRenderer()
        events = mutableListOf()
        val grid = SlotGrid(specs)
        val engine = ChestClickEngine(
            menu = mockk<Menu>(relaxed = true),
            grid = grid,
            menuSize = menuSize,
            hidePlayerInventory = hide,
            hasPlaceableSlot = specs.values.any { it.placeable },
            scheduler = InlineScheduler(),
            publish = { e ->
                events += e
                if (e is SlotPlaceEvent && cancelPlace) e.isCancelled = true
                if (e is SlotTakeEvent && cancelTake) e.isCancelled = true
            },
            renderer = renderer,
            isViewing = { true },
        )
        return engine to grid
    }

    private fun player(vararg bukkitSlotItems: Pair<Int, ItemStack>): Player {
        val inv = mockk<PlayerInventory>(relaxed = true)
        every { inv.getItem(any<Int>()) } returns null
        bukkitSlotItems.forEach { (slot, item) -> every { inv.getItem(slot) } returns item }
        return mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { isOnline } returns true
            every { inventory } returns inv
        }
    }

    private fun click(engine: ChestClickEngine, player: Player, windowSlot: Int, right: Boolean = false) {
        engine.submit(
            player,
            ChestClickEngine.ClickSnapshot(
                windowSlot = windowSlot,
                button = if (right) 1 else 0,
                clickType = WindowClickType.PICKUP,
                involvedSlots = listOf(windowSlot),
                bukkitClickType = if (right) ClickType.RIGHT else ClickType.LEFT,
            ),
        )
    }

    @Test
    fun `movable 槽位左键拿起：grid 清空、光标写入、重绘`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.STONE, 5), movable = true)))
        val p = player()
        click(engine, p, 5)
        assertTrue(grid[5]!!.item.type.isAir)
        assertEquals(Material.STONE, engine.cursorOf(p)!!.item.type)
        assertEquals(5, engine.cursorOf(p)!!.item.amount)
        assertEquals(listOf(5), renderer.repaints)
        assertEquals(Material.STONE, renderer.cursors.single()!!.type)
    }

    @Test
    fun `从背包拿起再放入 placeable 槽位：派发 SlotPlaceEvent 并提交`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.AIR), placeable = true)))
        val p = player(9 to ItemStack(Material.EMERALD, 3)) // 窗口槽 27 ↔ bukkit 9
        click(engine, p, 27)                                 // 视觉拿起
        assertIs<CursorOrigin.PlayerInventory>(engine.cursorOf(p)!!.origin)
        assertEquals(listOf(27), renderer.emptiedWindowSlots)
        click(engine, p, 5)                                  // 放入
        val place = events.filterIsInstance<SlotPlaceEvent>().single()
        assertEquals(5, place.index); assertEquals(9, place.sourceSlot); assertEquals(3, place.item.amount)
        assertEquals(Material.EMERALD, grid[5]!!.item.type)
        assertNull(engine.cursorOf(p))
        assertEquals(listOf(27), renderer.bottomResyncs)     // 渲染回调的真实扣除
    }

    @Test
    fun `SlotPlaceEvent 取消：grid 不变、光标保留`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.AIR), placeable = true)))
        val p = player(9 to ItemStack(Material.EMERALD, 3))
        click(engine, p, 27)
        cancelPlace = true
        click(engine, p, 5)
        assertTrue(grid[5]!!.item.type.isAir)
        assertEquals(Material.EMERALD, engine.cursorOf(p)!!.item.type)
    }

    @Test
    fun `菜单源光标落入背包区：派发 SlotTakeEvent（index=来源槽），提交后光标清空`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        val p = player()
        click(engine, p, 5)
        click(engine, p, 30) // 窗口槽 30 ↔ bukkit 12
        val take = events.filterIsInstance<SlotTakeEvent>().single()
        assertEquals(5, take.index); assertEquals(12, take.targetSlot); assertEquals(2, take.item.amount)
        assertNull(engine.cursorOf(p))
        assertEquals(listOf(30), renderer.bottomResyncs)
        assertTrue(grid[5]!!.item.type.isAir) // 拿起时已清空且未归还
    }

    @Test
    fun `SlotTakeEvent 取消：光标维持`() {
        val (engine, _) = engine(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        val p = player()
        click(engine, p, 5)
        cancelTake = true
        click(engine, p, 30)
        assertEquals(Material.DIAMOND, engine.cursorOf(p)!!.item.type)
    }

    @Test
    fun `非 PICKUP 点击一律拒绝并权威重刷`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        val p = player()
        engine.submit(
            p,
            ChestClickEngine.ClickSnapshot(5, 0, WindowClickType.QUICK_MOVE, listOf(5), ClickType.SHIFT_LEFT),
        )
        assertEquals(Material.DIAMOND, grid[5]!!.item.type)
        assertEquals(1, renderer.resyncedSlots.size)
        assertTrue(events.single() is InventoryClickEvent) // 兼容：信息性事件照发
    }

    @Test
    fun `onViewerRemoved 把菜单源光标归还来源槽位`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        val p = player()
        click(engine, p, 5)
        engine.onViewerRemoved(p)
        assertEquals(Material.DIAMOND, grid[5]!!.item.type)
        assertEquals(2, grid[5]!!.item.amount)
        assertNull(engine.cursorOf(p))
    }
}
