package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotUpdateLoop
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.overlay.builder.OverlaySlotBuilder
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.util.item
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** 立即同步执行每个被排任务一次的假调度器（并记录 isAsync）。 */
private class AsyncTrackingScheduler(val asyncFlags: MutableList<Boolean> = mutableListOf()) : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int { asyncFlags += task.isAsync; task.onTick(); return 0 }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

/** 记录调度/取消次数（不自动执行 onTick），供幂等性断言使用。 */
private class CountingScheduler : TaskScheduler {
    private var nextId = 0
    var scheduleCount = 0
        private set
    var cancelCount = 0
        private set

    override fun scheduleTask(task: TaskScheduler.Task): Int { scheduleCount++; return nextId++ }
    override fun cancelTask(taskId: Int): Boolean { cancelCount++; return true }
    override fun cancelAllTasks() {}
}

class SlotUpdateLoopTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test
    fun `update 规则异步 tick 并在物品变化时重绘`() {
        val scheduler = AsyncTrackingScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item = item(Material.CLOCK, 5) }
        }.build(item(Material.AIR))
        val map = SlotMap(mapOf(4 to spec))
        val repaints = mutableListOf<Int>()
        SlotUpdateLoop(map, scheduler, { repaints += it }).start()

        assertEquals(Material.CLOCK, map[4]!!.item.type)
        assertEquals(listOf(4), repaints)
        assertEquals(listOf(true), scheduler.asyncFlags) // overlay 保持异步
    }

    @Test
    fun `update 规则赋值的外部对象以副本存入，事后改动不波及内部`() {
        val scheduler = AsyncTrackingScheduler()
        val template = item(Material.CLOCK, 1) // 规则块持有的外部模板（惯用写法）
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item = template }
        }.build(item(Material.AIR))
        val map = SlotMap(mapOf(4 to spec))
        SlotUpdateLoop(map, scheduler) { }.start()

        assertEquals(Material.CLOCK, map[4]!!.item.type) // 赋值内容已生效
        template.amount = 99 // tick 之后外部继续改模板
        assertEquals(1, map[4]!!.item.amount) // 内部存的是副本，不受影响
    }

    @Test
    fun `同槽同 trigger 规则合并为一个任务，按 priority 串行且一次提交`() {
        val scheduler = AsyncTrackingScheduler()
        val spec = OverlaySlotBuilder().apply {
            // 声明顺序故意与 priority 相反；两个 Interval 独立构造，靠值相等归组
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(20)) {
                item.amount += 1 // 低优先级后执行：应看到高优先级的结果并在其上累加
            }
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(1)) {
                item = item(Material.CLOCK, 1) // 高优先级（小值）先执行
            }
        }.build(item(Material.PAPER, 1))
        val map = SlotMap(mapOf(4 to spec))
        val repaints = mutableListOf<Int>()
        SlotUpdateLoop(map, scheduler) { repaints += it }.start()

        assertEquals(Material.CLOCK, map[4]!!.item.type) // 先 CLOCK…
        assertEquals(2, map[4]!!.item.amount) // …后 +1，可见前序结果
        assertEquals(listOf(4), repaints) // 单次提交/重绘
        assertEquals(listOf(true), scheduler.asyncFlags) // 合并为一个任务（仍异步）
    }

    @Test
    fun `tick 内对本槽的直接写入优先于提案，不被过期提案回滚`() {
        val scheduler = AsyncTrackingScheduler()
        lateinit var mapRef: SlotMap
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) {
                item = item(Material.CLOCK, 2) // 本 tick 的提案
                mapRef[4]!!.item = item(Material.DIAMOND) // tick 内有人直接写入（setItem 的底层路径）
            }
        }.build(item(Material.PAPER))
        val map = SlotMap(mapOf(4 to spec))
        mapRef = map
        val repaints = mutableListOf<Int>()
        SlotUpdateLoop(map, scheduler) { repaints += it }.start()

        assertEquals(Material.DIAMOND, map[4]!!.item.type) // 直接写入胜出，过期提案作废
        assertEquals(emptyList(), repaints) // 提案未提交 → loop 不触发重绘
    }

    @Test
    fun `start 幂等，重复调用不重复调度任务`() {
        val scheduler = CountingScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { }
        }.build(item(Material.AIR))
        val map = SlotMap(mapOf(4 to spec))
        val loop = SlotUpdateLoop(map, scheduler) { }

        loop.start()
        assertEquals(1, scheduler.scheduleCount)
        loop.start() // 重复调用：已在运行，直接返回
        loop.start()
        assertEquals(1, scheduler.scheduleCount)
    }

    @Test
    fun `stop 后可重新 start，重新调度任务`() {
        val scheduler = CountingScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { }
        }.build(item(Material.AIR))
        val map = SlotMap(mapOf(4 to spec))
        val loop = SlotUpdateLoop(map, scheduler) { }

        loop.start()
        assertEquals(1, scheduler.scheduleCount)
        loop.stop()
        assertEquals(1, scheduler.cancelCount)
        loop.start() // 停止后重启：重新调度
        assertEquals(2, scheduler.scheduleCount)
    }
}
