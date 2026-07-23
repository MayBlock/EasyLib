package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.BukkitEasyLibApi
import com.github.mayblock.easylib.api.bukkit.scheduler.BukkitTaskExecutors
import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.overlay.builder.OverlaySlotBuilder
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotUpdateLoop
import com.github.mayblock.easylib.impl.bukkit.util.stack
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** 无操作 TaskScope：测试内联执行任务块时的运行期载体。 */
private object NoopScope : TaskScheduler.TaskScope {
    override fun cancel() {}
}

/**
 * 哨兵执行器：行为与 [TaskExecutor.Direct] 相同（直接执行），但**身份不同**。
 * scheduleSyncTask/scheduleAsyncTask 扩展函数在排程时把全局 `taskExecutors.sync/async`
 * 盖进 [TaskScheduler.Task.executor]；把哨兵注入全局单例后，断言
 * `task.executor === AsyncMarker` 即证明 loop 确实经 scheduleAsyncTask 排程——
 * 若它绕道裸 scheduleTask（默认 Direct）或误走 sync 路径，身份断言当场失败。
 */
private val SyncMarker = TaskExecutor { it() }
private val AsyncMarker = TaskExecutor { it() }

private val MarkerExecutors = object : BukkitTaskExecutors {
    override val sync: TaskExecutor get() = SyncMarker
    override val async: TaskExecutor get() = AsyncMarker
}

/** 记录被排程的 Task 并立即内联执行的假调度器（扩展函数最终都落到成员 scheduleTask）。 */
private class InlineScheduler : TaskScheduler {
    val tasks = mutableListOf<TaskScheduler.Task>()

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        tasks += task
        task.onTick(NoopScope)
        return 0
    }

    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

/** 记录调度/取消次数（不自动执行任务块），供幂等性断言使用。 */
private class CountingScheduler : TaskScheduler {
    private var nextId = 0
    var scheduleCount = 0
        private set
    var cancelCount = 0
        private set

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        scheduleCount++
        return nextId++
    }

    override fun cancelTask(taskId: Int): Boolean {
        cancelCount++
        return true
    }

    override fun cancelAllTasks() {}
}

class SlotUpdateLoopTest {

    private var previousApi: EasyLibApi? = null

    @BeforeTest
    fun setUp() {
        MockBukkit.mock()
        // scheduleAsyncTask 扩展在排程时读全局单例取 executor；注入哨兵使 async 路径可被身份断言。
        // 非 relaxed mock：除 taskExecutors 外的任何触碰都会快速失败，测试不静默依赖全局状态。
        previousApi = try { EasyLibApi.api } catch (_: UninitializedPropertyAccessException) { null }
        EasyLibApi.api = mockk<BukkitEasyLibApi> { every { taskExecutors } returns MarkerExecutors }
    }

    @AfterTest
    fun tearDown() {
        // 尽量恢复全局单例，避免 mock 泄漏到同 JVM 的后续测试类（lateinit 无法退回未初始化态）。
        previousApi?.let { EasyLibApi.api = it }
        MockBukkit.unmock()
    }

    @Test
    fun `update 规则经 scheduleAsyncTask 排程 tick，并在物品变化时重绘`() {
        val scheduler = InlineScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item = stack(Material.CLOCK, 5) }
        }.build(stack(Material.AIR))
        val map = SlotMap(mapOf(4 to spec))
        val repaints = mutableListOf<Int>()
        SlotUpdateLoop(map, scheduler) { repaints += it }.start()

        assertEquals(Material.CLOCK, map[4]!!.item.type)
        assertEquals(listOf(4), repaints)
        // 「overlay 走异步」是本类自身的契约：任务的 executor 必须是全局 async 哨兵。
        assertEquals(listOf(AsyncMarker), scheduler.tasks.map { it.executor })
    }

    @Test
    fun `update 规则赋值的外部对象以副本存入，事后改动不波及内部`() {
        val scheduler = InlineScheduler()
        val template = stack(Material.CLOCK, 1) // 规则块持有的外部模板（惯用写法）
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item = template }
        }.build(stack(Material.AIR))
        val map = SlotMap(mapOf(4 to spec))
        SlotUpdateLoop(map, scheduler) { }.start()

        assertEquals(Material.CLOCK, map[4]!!.item.type) // 赋值内容已生效
        template.amount = 99 // tick 之后外部继续改模板
        assertEquals(1, map[4]!!.item.amount) // 内部存的是副本，不受影响
    }

    @Test
    fun `同槽同 trigger 规则合并为一个任务，按 priority 串行且一次提交`() {
        val scheduler = InlineScheduler()
        val spec = OverlaySlotBuilder().apply {
            // 声明顺序故意与 priority 相反；两个 Interval 独立构造，靠值相等归组
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(20)) {
                item.amount += 1 // 低优先级后执行：应看到高优先级的结果并在其上累加
            }
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(1)) {
                item = stack(Material.CLOCK, 1) // 高优先级（小值）先执行
            }
        }.build(stack(Material.PAPER, 1))
        val map = SlotMap(mapOf(4 to spec))
        val repaints = mutableListOf<Int>()
        SlotUpdateLoop(map, scheduler) { repaints += it }.start()

        assertEquals(Material.CLOCK, map[4]!!.item.type) // 先 CLOCK…
        assertEquals(2, map[4]!!.item.amount) // …后 +1，可见前序结果
        assertEquals(listOf(4), repaints) // 单次提交/重绘
        // 只排了一个任务 ⇒ 两条同 trigger 规则确实合并；且仍走 async 路径。
        assertEquals(listOf(AsyncMarker), scheduler.tasks.map { it.executor })
    }

    @Test
    fun `tick 内对本槽的直接写入优先于提案，不被过期提案回滚`() {
        val scheduler = InlineScheduler()
        lateinit var mapRef: SlotMap
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) {
                item = stack(Material.CLOCK, 2) // 本 tick 的提案
                mapRef[4]!!.item = stack(Material.DIAMOND) // tick 内有人直接写入（setItem 的底层路径）
            }
        }.build(stack(Material.PAPER))
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
        }.build(stack(Material.AIR))
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
        }.build(stack(Material.AIR))
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
