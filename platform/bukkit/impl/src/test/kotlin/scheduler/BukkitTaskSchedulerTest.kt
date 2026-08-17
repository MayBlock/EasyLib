package com.github.mayblock.easylib.base.impl.bukkit.scheduler

import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler.Trigger
import com.github.mayblock.easylib.base.api.scheduler.scheduleTask
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 钉住 [BukkitTaskScheduler] 的线程契约：Bukkit 调度器只决定「何时触发」，
 * 触发后回调必须经 `Task.executor` 执行——否则 `scheduleAsyncTask` 之类的 executor 选择形同虚设。
 */
class BukkitTaskSchedulerTest {

    private lateinit var server: ServerMock
    private lateinit var scheduler: BukkitTaskScheduler

    @BeforeTest
    fun setUp() {
        server = MockBukkit.mock()
        scheduler = BukkitTaskScheduler(MockBukkit.createMockPlugin())
    }

    @AfterTest
    fun tearDown() {
        MockBukkit.unmock()
    }

    /** 记录自己被调用次数、并就地执行任务的执行器：调用次数 == 经由它执行的 tick 数。 */
    private class CountingExecutor : TaskExecutor {
        var invocations = 0
        override fun execute(task: () -> Unit) {
            invocations++
            task()
        }
    }

    @Test
    fun `一次性任务经 executor 执行且执行后自删`() {
        val executor = CountingExecutor()
        var ran = 0
        val id = scheduler.scheduleTask(Trigger.Once, executor) { ran++ }

        server.scheduler.performOneTick()

        assertEquals(1, ran)
        assertEquals(1, executor.invocations)
        // 任务已完成并自删，再次取消应返回 false
        assertFalse(scheduler.cancelTask(id))
    }

    @Test
    fun `周期任务每个 tick 都经 executor 执行，cancel 后停止`() {
        val executor = CountingExecutor()
        var ran = 0
        scheduler.scheduleTask(Trigger.Interval(50.milliseconds), executor) {
            if (++ran == 3) cancel()
        }

        server.scheduler.performTicks(10)

        assertEquals(3, ran)
        assertEquals(3, executor.invocations)
    }

    @Test
    fun `延迟任务在延迟到期前不触发`() {
        var ran = false
        val id = scheduler.scheduleTask(Trigger.Delay(200.milliseconds)) { ran = true }

        server.scheduler.performTicks(3)
        assertFalse(ran)
        assertTrue(scheduler.cancelTask(id))

        server.scheduler.performTicks(5)
        assertFalse(ran)
    }
}
