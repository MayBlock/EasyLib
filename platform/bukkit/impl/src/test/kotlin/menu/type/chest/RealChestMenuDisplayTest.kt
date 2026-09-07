package com.github.mayblock.easylib.platform.bukkit.impl.menu.type.chest

import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.dsl.item
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.SlotSpec
import com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.platform.bukkit.impl.testing.TestSyncContext
import com.github.mayblock.easylib.platform.bukkit.impl.util.stack
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import org.bukkit.event.inventory.InventoryClickEvent as BukkitInventoryClickEvent

/**
 * 手动泵调度器（与 RealChestMenuUpdateTest 同构；本文件独立副本，测试文件间不共享私有类）。
 * 命名与 RealChestMenuUpdateTest 的同名私有类区分——Kotlin 顶层 `private` 只限制可见性，
 * 同包同名仍是二进制层面的 Redeclaration，故用 `Display` 前缀避免冲突。
 */
private class DisplayPumpScheduler : TaskScheduler {
    private var nextId = 0
    val queue = ArrayDeque<TaskScheduler.Task>()
    override fun scheduleTask(task: TaskScheduler.Task): Int { queue += task; return nextId++ }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
    fun pump() {
        val round = queue.toList()
        queue.clear()
        round.forEach {
            it.onTick(mockk<TaskScheduler.TaskScope>(relaxed = true))
        }
    }
}

class RealChestMenuDisplayTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    private val syncExecutor = TaskExecutor { it() }
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    /** 美化规则：固定假物品 + 标记显示名，便于断言条目存在（不依赖基底类型）。 */
    private fun fancySpec(base: Material, allowTake: Boolean = false): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java).apply {
            item(base)
            if (allowTake) onTake { isCancelled = false }
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem = ItemStack(Material.NETHER_STAR)
                    .also { it.itemMeta = it.itemMeta?.apply { setDisplayName("§b美化") } }
            }
        }.build()

    private fun menu(scheduler: TaskScheduler, specs: Map<Int, SlotSpec>, pm: PacketManager<*> = mockk(relaxed = true)) =
        RealChestMenu(
            scheduler,
            TestSyncContext(syncExecutor),
            pm,
            Component.text("t"),
            ChestMenuType.GENERIC_9X3,
            specs,
            hidePlayerInventory = false,
        )

    private fun click(view: InventoryView, rawSlot: Int, action: InventoryAction): BukkitInventoryClickEvent =
        BukkitInventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, ClickType.LEFT, action)

    @Test fun `handleOpen 立即种子填充显示缓存（首帧前）`() {
        val scheduler = DisplayPumpScheduler()
        val m = menu(scheduler, mapOf(4 to fancySpec(Material.DIAMOND)))
        val p = server.addPlayer()
        m.handleOpen(p) // 种子同步执行，不依赖任务泵
        assertEquals("§b美化", m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.itemMeta!!.displayName)
    }

    @Test fun `handleClose 清理该 viewer 的显示缓存`() {
        val scheduler = DisplayPumpScheduler()
        val m = menu(scheduler, mapOf(4 to fancySpec(Material.DIAMOND)))
        val p = server.addPlayer()
        m.handleOpen(p)
        assertNotNull(m.displayMap.lookup(p.uniqueId, 4))
        m.handleClose(p)
        assertNull(m.displayMap.lookup(p.uniqueId, 4))
    }

    @Test fun `setItem 同步重算：条目以新基底重建`() {
        val scheduler = DisplayPumpScheduler()
        val s = SlotBuilder(InventoryClickEvent::class.java).apply {
            item(Material.DIAMOND)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem.itemMeta = displayItem.itemMeta?.apply { setDisplayName("§b美化") }
            }
        }.build()
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)
        assertEquals(Material.DIAMOND, m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.type)
        m.setItem(4, stack(Material.EMERALD, 7)) // 已知新值路径 → 同步重算
        val entry = m.displayMap.lookup(p.uniqueId, 4)!!
        assertEquals(Material.EMERALD, entry.bukkitItem.type)
        assertEquals(7, entry.bukkitItem.amount)
        assertEquals("§b美化", entry.bukkitItem.itemMeta!!.displayName)
    }

    @Test fun `放行取出：条目立即失效（透传真实），下一 tick 重算恢复`() {
        val scheduler = DisplayPumpScheduler()
        val m = menu(scheduler, mapOf(5 to fancySpec(Material.DIAMOND, allowTake = true)))
        val p = server.addPlayer()
        val view = p.openInventory(m.inventory)!!
        m.handleOpen(p)
        assertNotNull(m.displayMap.lookup(p.uniqueId, 5))
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertFalse(e.isCancelled)
        assertNull(m.displayMap.lookup(p.uniqueId, 5)) // 立即失效，改写层透传真实
        val recomputeTask = scheduler.queue.single { it.trigger == TaskScheduler.Trigger.Once }
        assertSame(syncExecutor, recomputeTask.executor)
        scheduler.pump() // 下一 tick：重算（MockBukkit 不执行原生移动，容器仍旧值 → 条目恢复）
        assertNotNull(m.displayMap.lookup(p.uniqueId, 5))
    }

    @Test fun `拒绝取出：条目保持（回滚包被改写层接住，零闪烁路径）`() {
        val scheduler = DisplayPumpScheduler()
        val m = menu(scheduler, mapOf(5 to fancySpec(Material.DIAMOND, allowTake = false)))
        val p = server.addPlayer()
        val view = p.openInventory(m.inventory)!!
        m.handleOpen(p)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
        assertNotNull(m.displayMap.lookup(p.uniqueId, 5))
    }

    @Test fun `有 update 规则才注册显示改写监听器`() {
        val pmWith = mockk<PacketManager<*>>(relaxed = true)
        menu(DisplayPumpScheduler(), mapOf(4 to fancySpec(Material.DIAMOND)), pmWith)
        // hidePlayerInventory=false ⇒ 唯一的 registerListener 来自 displayMask
        verify(exactly = 1) { pmWith.registerListener(any()) }
        val pmWithout = mockk<PacketManager<*>>(relaxed = true)
        val plain = SlotBuilder(InventoryClickEvent::class.java).apply { item(Material.DIAMOND) }.build()
        menu(DisplayPumpScheduler(), mapOf(4 to plain), pmWithout)
        verify(exactly = 0) { pmWithout.registerListener(any()) }
    }
}
