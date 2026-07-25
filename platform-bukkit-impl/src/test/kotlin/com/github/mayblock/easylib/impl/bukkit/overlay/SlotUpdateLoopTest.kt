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
import com.github.mayblock.easylib.impl.bukkit.util.SlotDisplayMap
import com.github.mayblock.easylib.impl.bukkit.util.stack
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** 无操作 TaskScope：测试内联执行任务块时的运行期载体。 */
private object NoopScope : TaskScheduler.TaskScope {
    override fun cancel() {}
}

/**
 * 哨兵执行器：行为与 [TaskExecutor.Direct] 相同（直接执行），但**身份不同**。
 * scheduleSyncTask/scheduleAsyncTask 扩展函数在排程时把全局 `taskExecutors.sync/async`
 * 盖进 [TaskScheduler.Task.executor]；把哨兵注入全局单例后，断言
 * `task.executor === SyncMarker` 即证明 loop 确实经 scheduleSyncTask 排程——
 * 若它绕道裸 scheduleTask（默认 Direct）或误走 async 路径，身份断言当场失败。
 *
 * 注意这只钉住**排程路径**，不代表**线程落地**：生产环境唯一的 `TaskScheduler` 实现
 * （`BukkitTaskSchedulerImpl`）从不读 `Task.executor`，scheduleSyncTask/scheduleAsyncTask 派发的任务
 * 最终都经 `Bukkit.getScheduler().runTask` 系列落地，实际执行线程由那一层决定，与本断言无关。
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
        // scheduleSyncTask 扩展在排程时读全局单例取 executor；注入哨兵使 sync 路径可被身份断言。
        // 非 relaxed mock：除 taskExecutors 外的任何触碰都会快速失败，测试不静默依赖全局状态。
        previousApi = try { EasyLibApi.api } catch (_: UninitializedPropertyAccessException) { null }
        EasyLibApi.api = mockk<BukkitEasyLibApi> { every { taskExecutors } returns MarkerExecutors }
    }

    @AfterTest
    fun tearDown() {
        previousApi?.let { EasyLibApi.api = it }
        MockBukkit.unmock()
    }

    private fun player(): Player = mockk<Player>(relaxed = true).also {
        every { it.uniqueId } returns UUID.randomUUID()
    }

    @Test
    fun `update 规则经 scheduleSyncTask 排程，对每个 viewer 各跑一次并提交显示层`() {
        val scheduler = InlineScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item = stack(Material.CLOCK, 5) }
        }.build(stack(Material.PAPER))
        val map = SlotMap(mapOf(4 to spec))
        val display = SlotDisplayMap()
        val a = player()
        val b = player()
        val repaints = mutableListOf<Pair<Player, Int>>()
        SlotUpdateLoop(map, scheduler, { listOf(a, b) }, display) { p, i -> repaints += p to i }.start()

        // 基底未被写回（本设计的核心：onUpdate 不再改共享态）
        assertEquals(Material.PAPER, map[4]!!.item.type)
        // 两个 viewer 各得一份显示条目
        assertEquals(Material.CLOCK, display.lookup(a.uniqueId, 4)!!.bukkitItem.type)
        assertEquals(Material.CLOCK, display.lookup(b.uniqueId, 4)!!.bukkitItem.type)
        assertEquals(listOf(a to 4, b to 4), repaints)
        // 本类的契约是「overlay 更新走 scheduleSyncTask 排程路径」：executor 必须命中全局 sync 哨兵。
        // 这证明的是排程路径而非线程落地——生产 scheduler 忽略 Task.executor（见上方 SyncMarker KDoc）。
        assertEquals(listOf(SyncMarker), scheduler.tasks.map { it.executor })
    }

    @Test
    fun `不同 viewer 可得不同显示，互不影响`() {
        val scheduler = InlineScheduler()
        val a = player()
        val b = player()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) {
                item = if (player.uniqueId == a.uniqueId) stack(Material.DIAMOND) else stack(Material.EMERALD)
            }
        }.build(stack(Material.PAPER))
        val map = SlotMap(mapOf(4 to spec))
        val display = SlotDisplayMap()
        SlotUpdateLoop(map, scheduler, { listOf(a, b) }, display) { _, _ -> }.start()

        assertEquals(Material.DIAMOND, display.lookup(a.uniqueId, 4)!!.bukkitItem.type)
        assertEquals(Material.EMERALD, display.lookup(b.uniqueId, 4)!!.bukkitItem.type)
    }

    @Test
    fun `scope 暴露 index 与 player`() {
        val scheduler = InlineScheduler()
        val a = player()
        val seen = mutableListOf<Pair<Int, Player>>()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { seen += index to player }
        }.build(stack(Material.PAPER))
        SlotUpdateLoop(SlotMap(mapOf(4 to spec)), scheduler, { listOf(a) }, SlotDisplayMap()) { _, _ -> }.start()

        assertEquals(listOf(4 to a), seen)
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
        val display = SlotDisplayMap()
        val a = player()
        val repaints = mutableListOf<Pair<Player, Int>>()
        SlotUpdateLoop(SlotMap(mapOf(4 to spec)), scheduler, { listOf(a) }, display) { p, i -> repaints += p to i }
            .start()

        val shown = display.lookup(a.uniqueId, 4)!!.bukkitItem
        assertEquals(Material.CLOCK, shown.type) // 先 CLOCK…
        assertEquals(2, shown.amount)            // …后 +1，可见前序结果
        assertEquals(listOf(a to 4), repaints)   // 单次提交/重绘
        assertEquals(listOf(SyncMarker), scheduler.tasks.map { it.executor })
    }

    @Test
    fun `规则不改物品则不提交条目、不重绘`() {
        val scheduler = InlineScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { /* 什么都不改 */ }
        }.build(stack(Material.PAPER))
        val display = SlotDisplayMap()
        val a = player()
        val repaints = mutableListOf<Pair<Player, Int>>()
        SlotUpdateLoop(SlotMap(mapOf(4 to spec)), scheduler, { listOf(a) }, display) { p, i -> repaints += p to i }
            .start()

        assertNull(display.lookup(a.uniqueId, 4)) // 与基底一致 → 无条目 → 出站回落基底
        assertEquals(emptyList(), repaints)
    }

    @Test
    fun `seed 为迟到观察者补齐条目但不重绘`() {
        val scheduler = InlineScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item = stack(Material.CLOCK) }
        }.build(stack(Material.PAPER))
        val display = SlotDisplayMap()
        val late = player()
        val repaints = mutableListOf<Pair<Player, Int>>()
        // 启动时无观察者；随后 late 才加入
        val loop = SlotUpdateLoop(SlotMap(mapOf(4 to spec)), scheduler, { emptyList() }, display) { p, i ->
            repaints += p to i
        }
        loop.start()
        assertNull(display.lookup(late.uniqueId, 4))

        loop.seed(late)
        assertEquals(Material.CLOCK, display.lookup(late.uniqueId, 4)!!.bukkitItem.type)
        assertEquals(emptyList(), repaints) // seed 不发包：首帧由 paintAll 承载
    }

    @Test
    fun `Delay 组未到期时 seed 不执行，已触发过则补齐`() {
        val trigger = TaskScheduler.Trigger.Delay(1.seconds)
        val spec = OverlaySlotBuilder().apply {
            onUpdate(trigger) { item = stack(Material.CLOCK) }
        }.build(stack(Material.PAPER))
        val display = SlotDisplayMap()
        val late = player()

        // CountingScheduler 不执行任务块 ⇒ Delay 组尚未触发过
        val notFired = SlotUpdateLoop(SlotMap(mapOf(4 to spec)), CountingScheduler(), { emptyList() }, display) { _, _ -> }
        notFired.start()
        notFired.seed(late)
        assertNull(display.lookup(late.uniqueId, 4)) // 未到期 → 尊重延迟语义

        // InlineScheduler 立即执行任务块 ⇒ Delay 组已触发过
        val fired = SlotUpdateLoop(SlotMap(mapOf(4 to spec)), InlineScheduler(), { emptyList() }, display) { _, _ -> }
        fired.start()
        fired.seed(late)
        assertEquals(Material.CLOCK, display.lookup(late.uniqueId, 4)!!.bukkitItem.type)
    }

    @Test
    fun `recomputeSlot 以新基底重算全 viewer 但不重绘`() {
        val scheduler = InlineScheduler()
        val spec = OverlaySlotBuilder().apply {
            // 显示 = 基底类型 + 数量翻倍，便于观察基底变更是否被吸收
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item.amount = item.amount * 2 }
        }.build(stack(Material.PAPER, 1))
        val map = SlotMap(mapOf(4 to spec))
        val display = SlotDisplayMap()
        val a = player()
        val repaints = mutableListOf<Pair<Player, Int>>()
        val loop = SlotUpdateLoop(map, scheduler, { listOf(a) }, display) { p, i -> repaints += p to i }
        loop.start()
        assertEquals(2, display.lookup(a.uniqueId, 4)!!.bukkitItem.amount)

        map[4]!!.item = stack(Material.PAPER, 5) // 模拟 setItem 写基底
        repaints.clear()
        loop.recomputeSlot(4)

        assertEquals(10, display.lookup(a.uniqueId, 4)!!.bukkitItem.amount) // 以新基底重算
        assertEquals(emptyList(), repaints) // 发包权归调用方（PlayerOverlayImpl.setItem）
    }

    @Test
    fun `start 幂等，重复调用不重复调度任务`() {
        val scheduler = CountingScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { }
        }.build(stack(Material.AIR))
        val loop = SlotUpdateLoop(SlotMap(mapOf(4 to spec)), scheduler, { emptyList() }, SlotDisplayMap()) { _, _ -> }

        loop.start()
        assertEquals(1, scheduler.scheduleCount)
        loop.start() // 重复调用：已在运行，直接返回
        loop.start()
        assertEquals(1, scheduler.scheduleCount)
    }

    @Test
    fun `stop 后可重新 start，且 stop 清空显示层`() {
        val scheduler = CountingScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { }
        }.build(stack(Material.AIR))
        val display = SlotDisplayMap()
        val a = player()
        display.commit(a.uniqueId, 4, stack(Material.AIR), stack(Material.CLOCK))
        val loop = SlotUpdateLoop(SlotMap(mapOf(4 to spec)), scheduler, { emptyList() }, display) { _, _ -> }

        loop.start()
        assertEquals(1, scheduler.scheduleCount)
        loop.stop()
        assertEquals(1, scheduler.cancelCount)
        assertNull(display.lookup(a.uniqueId, 4)) // 停机即清显示层，防陈旧条目跨激活周期存活
        loop.start() // 停止后重启：重新调度
        assertEquals(2, scheduler.scheduleCount)
    }
}
