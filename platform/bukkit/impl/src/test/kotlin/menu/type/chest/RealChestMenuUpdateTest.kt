package com.github.mayblock.easylib.platform.bukkit.impl.menu.type.chest

import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.util.Priority
import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.dsl.item
import com.github.mayblock.easylib.platform.bukkit.api.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.SlotSpec
import com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.platform.bukkit.impl.scheduler.BukkitTaskExecutorsImpl
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** 立即同步执行每个被排的任务一次的假调度器（断言 isAsync=false）。 */
private class AsyncTrackingScheduler(
    val asyncFlags: MutableList<Boolean> = mutableListOf()
) : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int {
        asyncFlags += task.executor is BukkitTaskExecutorsImpl.AsyncExecutor; task.onTick(
            mockk<TaskScheduler.TaskScope>(relaxed = true)
        ); return 0
    }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

/** 立即同步执行每个被排任务一次，同时记录调度/取消次数，供按需启停断言使用。 */
private class RecordingScheduler : TaskScheduler {
    private var nextId = 0
    val scheduledIds = mutableListOf<Int>()
    val cancelledIds = mutableListOf<Int>()
    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = nextId++; scheduledIds += id; task.onTick(
            mockk<TaskScheduler.TaskScope>(relaxed = true)
        ); return id
    }
    override fun cancelTask(taskId: Int): Boolean { cancelledIds += taskId; return true }
    override fun cancelAllTasks() {}
}

/** 手动泵：任务入队不执行，pump() 按序执行一轮（可观察 invalidate 与重算之间的中间态）。 */
private class PumpScheduler : TaskScheduler {
    private var nextId = 0
    val queue = ArrayDeque<TaskScheduler.Task>()
    override fun scheduleTask(task: TaskScheduler.Task): Int { queue += task; return nextId++ }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
    fun pump() { val round = queue.toList(); queue.clear(); round.forEach { it.onTick(
        mockk<TaskScheduler.TaskScope>(relaxed = true)
    ) } }
}

class RealChestMenuUpdateTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock

    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun menu(scheduler: TaskScheduler, specs: Map<Int, SlotSpec>) =
        RealChestMenu(scheduler, mockk<PacketManager<*>>(relaxed = true), Component.text("t"), ChestMenuType.GENERIC_9X3, specs, hidePlayerInventory = false)

    private fun spec(block: SlotBuilder<InventoryClickEvent>.() -> Unit): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java).apply(block).build()

    @Test fun `更新规则在主线程 tick，写显示缓存而非真实容器`() {
        val scheduler = AsyncTrackingScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem = ItemStack(Material.CLOCK, 5)
            }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)
        // 真实容器不被写回（写回已删的回归断言）
        assertEquals(Material.PAPER, m.inventory.getItem(4)!!.type)
        // 显示缓存有该 viewer 的假物品
        assertEquals(Material.CLOCK, m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.type)
        assertEquals(listOf(false), scheduler.asyncFlags.take(1)) // 更新任务在主线程
    }

    @Test fun `事件携带正确 player，双 viewer 显示独立`() {
        val scheduler = AsyncTrackingScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem = ItemStack(Material.PAPER).also { it.itemMeta = it.itemMeta?.apply { setDisplayName(viewer.name) } }
            }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p1 = server.addPlayer(); val p2 = server.addPlayer()
        m.handleOpen(p1); m.handleOpen(p2)
        val d1 = m.displayMap.lookup(p1.uniqueId, 4)!!.bukkitItem
        val d2 = m.displayMap.lookup(p2.uniqueId, 4)!!.bukkitItem
        assertEquals(p1.name, d1.itemMeta!!.displayName)
        assertEquals(p2.name, d2.itemMeta!!.displayName)
        assertNotEquals(d1, d2)
    }

    @Test fun `真实基底重算：amount 递增不跨周期累积`() {
        val scheduler = PumpScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) { displayItem.amount += 1 }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)          // 种子计算一次：base=1 → display=2
        scheduler.pump()         // Interval 任务再计算一次：仍从真实基底 1 出发 → display=2（不累积）
        assertEquals(2, m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.amount)
        assertEquals(1, m.inventory.getItem(4)!!.amount) // 真实层不动
    }

    @Test fun `同槽同 trigger 规则合并，按 priority 串行`() {
        val scheduler = AsyncTrackingScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(20)) {
                displayItem.amount += 1 // 低优先级后执行：在高优先级结果上累加
            }
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(1)) {
                displayItem = ItemStack(Material.CLOCK, 1) // 高优先级（小值）先执行
            }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)
        val d = m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem
        assertEquals(Material.CLOCK, d.type)
        assertEquals(2, d.amount)
    }

    @Test fun `规则无修改则不留条目（透传真实）`() {
        val scheduler = AsyncTrackingScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) { /* 观察但不改 */ }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)
        assertNull(m.displayMap.lookup(p.uniqueId, 4))
    }

    @Test fun `脏 viewer 合并重绘：同 tick 两槽变更只排一个 flush 任务`() {
        val scheduler = PumpScheduler()
        // 计数器让每次计算结果都不同 ⇒ 每次 Interval 触发都判脏（种子结果≠首个 tick 结果）
        var n1 = 0; var n2 = 0
        val s1 = spec { item(Material.PAPER); onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) { displayItem = ItemStack(Material.CLOCK, ++n1) } }
        val s2 = spec { item(Material.PAPER); onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) { displayItem = ItemStack(Material.DIAMOND, ++n2) } }
        val m = menu(scheduler, mapOf(0 to s1, 1 to s2))
        val p = server.addPlayer()
        m.handleOpen(p)                       // 种子不标脏 → 队列里只有 2 个 Interval 任务
        assertEquals(2, scheduler.queue.size)
        scheduler.pump()                      // 两槽各判脏 → 各 markDirty → 只排 1 个 flush 任务
        assertEquals(1, scheduler.queue.size) // 合并证据：不是 2 个 flush（重绘副作用为 PlayerMock.updateInventory，空实现）
        scheduler.pump()                      // flush 执行，队列清空
        assertEquals(0, scheduler.queue.size)
    }

    @Test fun `不同 trigger 组各自从真实基底重算，后触发者整体覆盖`() {
        val scheduler = PumpScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem = ItemStack(Material.CLOCK, 9)
            }
            onUpdate(trigger = TaskScheduler.Trigger.Interval(2.seconds)) {
                // 独立组：看不到 1s 组的结果，displayItem 是真实基底（PAPER）的克隆
                assertEquals(Material.PAPER, displayItem.type)
                displayItem = ItemStack(Material.DIAMOND, 1)
            }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p) // 种子按声明序跑两组 → last-wins = DIAMOND
        assertEquals(Material.DIAMOND, m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.type)
    }

    @Test fun `update loop 按观察者存在与否启停，幂等且可重启`() {
        val scheduler = RecordingScheduler()
        val s = spec { onUpdate(trigger = TaskScheduler.Trigger.Once) { } }
        val m = menu(scheduler, mapOf(0 to s))
        assertEquals(0, scheduler.scheduledIds.size)
        val p1 = server.addPlayer()
        m.handleOpen(p1)
        assertEquals(1, scheduler.scheduledIds.size)
        val p2 = server.addPlayer()
        m.handleOpen(p2)
        assertEquals(1, scheduler.scheduledIds.size)
        m.handleClose(p1)
        assertEquals(0, scheduler.cancelledIds.size)
        m.handleClose(p2)
        assertEquals(1, scheduler.cancelledIds.size)
        m.handleOpen(p1)
        assertEquals(2, scheduler.scheduledIds.size)
        m.destroy()
        assertEquals(2, scheduler.cancelledIds.size)
    }
}
