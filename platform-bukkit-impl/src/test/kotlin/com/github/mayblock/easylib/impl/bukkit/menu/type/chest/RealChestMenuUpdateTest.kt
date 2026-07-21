package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.scheduler.BukkitAsyncExecutor
import com.github.mayblock.easylib.impl.bukkit.util.stack
import com.github.mayblock.easylib.packetevents.PacketManager
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** 立即同步执行每个被排的任务一次的假调度器（断言 isAsync=false）。 */
private class AsyncTrackingScheduler(
    val asyncFlags: MutableList<Boolean> = mutableListOf()
) : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int {
        asyncFlags += task.executor is BukkitAsyncExecutor; task.onTick(); return 0
    }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

/** 立即同步执行每个被排任务一次，同时记录调度/取消次数，供按需启停断言使用（对照 overlay 侧同名模式）。 */
private class RecordingScheduler : TaskScheduler {
    private var nextId = 0
    val scheduledIds = mutableListOf<Int>()
    val cancelledIds = mutableListOf<Int>()

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = nextId++
        scheduledIds += id
        task.onTick()
        return id
    }

    override fun cancelTask(taskId: Int): Boolean {
        cancelledIds += taskId
        return true
    }

    override fun cancelAllTasks() {}
}

class RealChestMenuUpdateTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock

    @BeforeTest
    fun setUp() {
        server = MockBukkit.mock()
    }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun menu(scheduler: TaskScheduler, specs: Map<Int, SlotSpec>) =
        RealChestMenu(scheduler, mockk<PacketManager<*>>(relaxed = true), Component.text("t"), ChestMenuType.GENERIC_9X3, specs, hidePlayerInventory = false)

    @Test fun `更新规则在主线程 tick 并写入真实容器`() {
        val scheduler = AsyncTrackingScheduler()
        val spec = SlotBuilder(InventoryClickEvent::class.java).apply {
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                // 回调体内不可隐式调用外层 SlotScope 的 item()（@SlotDsl 屏蔽，防止误改槽声明），
                // 构造物品用裸 ItemStack（或自备工厂）。
                item = ItemStack(Material.CLOCK, 5)
            }
        }.build()
        val m = menu(scheduler, mapOf(4 to spec))
        // 按需启停：首个观察者出现（handleOpen）才启动 → AsyncTrackingScheduler 立即执行一次 onTick
        m.handleOpen(server.addPlayer())
        assertEquals(Material.CLOCK, m.inventory.getItem(4)!!.type)
        assertEquals(5, m.inventory.getItem(4)!!.amount)
        assertEquals(listOf(false), scheduler.asyncFlags) // 主线程（非异步）
    }

    @Test fun `同槽同 trigger 规则合并为一个任务，按 priority 串行且一次写入`() {
        val scheduler = AsyncTrackingScheduler()
        val spec = SlotBuilder(InventoryClickEvent::class.java).apply {
            item(Material.PAPER)
            // 声明顺序故意与 priority 相反；两个 Interval 独立构造，靠值相等归组
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(20)) {
                item.amount += 1 // 低优先级后执行：应看到高优先级的结果并在其上累加
            }
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(1)) {
                item = ItemStack(Material.CLOCK, 1) // 高优先级（小值）先执行
            }
        }.build()
        val m = menu(scheduler, mapOf(4 to spec))
        m.handleOpen(server.addPlayer())
        assertEquals(Material.CLOCK, m.inventory.getItem(4)!!.type)
        assertEquals(2, m.inventory.getItem(4)!!.amount) // 串行可见前序结果
        assertEquals(listOf(false), scheduler.asyncFlags) // 合并为一个任务（仍主线程）
    }

    @Test fun `更新规则读到的是容器当前物品，而非声明期初始物品`() {
        val scheduler = AsyncTrackingScheduler()
        val seen = mutableListOf<Material>()
        val spec = SlotBuilder(InventoryClickEvent::class.java).apply {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) { seen += item.type }
        }.build()
        val m = menu(scheduler, mapOf(4 to spec))
        m.setItem(4, stack(Material.DIAMOND)) // 声明后、tick 前，容器被直接改写
        m.handleOpen(server.addPlayer())
        assertEquals(listOf(Material.DIAMOND), seen) // 读容器当前值，不是声明期的 PAPER
    }

    // ---- 按需启停（本次需求的验收，对照 overlay 侧 PlayerOverlayImplTest 同名测试）----

    @Test fun `update loop 按观察者存在与否启停，幂等且可重启`() {
        val scheduler = RecordingScheduler()
        val spec = SlotBuilder(InventoryClickEvent::class.java).apply {
            onUpdate(trigger = TaskScheduler.Trigger.Once) { }
        }.build()
        val m = menu(scheduler, mapOf(0 to spec))

        // ① 构造后（有 update 规则）不调度任何任务
        assertEquals(0, scheduler.scheduledIds.size)

        // ② 首个观察者 -> 任务被调度
        val p1 = server.addPlayer()
        m.handleOpen(p1)
        assertEquals(1, scheduler.scheduledIds.size)

        // ③ 第二个观察者加入 -> 不重复调度（幂等）
        val p2 = server.addPlayer()
        m.handleOpen(p2)
        assertEquals(1, scheduler.scheduledIds.size)

        // 还有一个观察者在场时 handleClose 不应停止
        m.handleClose(p1)
        assertEquals(0, scheduler.cancelledIds.size)

        // ④ 最后一个观察者离开 -> 任务被取消
        m.handleClose(p2)
        assertEquals(1, scheduler.cancelledIds.size)

        // ⑤ 再次打开 -> 重新调度
        m.handleOpen(p1)
        assertEquals(2, scheduler.scheduledIds.size)

        // ⑥ destroy -> 取消
        m.destroy()
        assertEquals(2, scheduler.cancelledIds.size)
    }
}
