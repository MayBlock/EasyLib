package com.github.mayblock.easylib.base.impl.util

import com.github.mayblock.easylib.base.api.event.on
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 手动泵调度器：不关心 Trigger 周期，每次 [tick] 同步触发一轮全部在册任务。
 * 支持任务在 onTick 内经 cancelTask/TaskScope.cancel 取消自身（Counter 自动完成路径依赖这一点）。
 */
private class PumpScheduler : TaskScheduler {
    private val tasks = LinkedHashMap<Int, TaskScheduler.Task>()
    private var nextId = 1
    val activeCount get() = tasks.size

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = nextId++
        tasks[id] = task
        return id
    }

    override fun cancelTask(taskId: Int): Boolean = tasks.remove(taskId) != null
    override fun cancelAllTasks() = tasks.clear()

    fun tick() {
        tasks.entries.toList().forEach { (id, task) ->
            if (id in tasks) task.onTick(object : TaskScheduler.TaskScope {
                override fun cancel() { tasks.remove(id) }
            })
        }
    }
}

class CounterTest {

    private val scheduler = PumpScheduler()

    private fun recordEvents(counter: Counter): MutableList<Counter.Event> {
        val events = mutableListOf<Counter.Event>()
        counter.on {
            on<Counter.Event.Started> { events += this }
            on<Counter.Event.Tick> { events += this }
            on<Counter.Event.Stopped> { events += this }
        }
        return events
    }

    @Test
    fun `Started 携带启动时的真实计数值而非构造初值`() {
        val counter = Counter(50.milliseconds, initialValue = 10)
        val events = recordEvents(counter)
        counter.set(3)
        counter.start(scheduler)
        assertEquals(listOf<Counter.Event>(Counter.Event.Started(3)), events)
    }

    @Test
    fun `Tick 按 step 步进`() {
        val counter = Counter(50.milliseconds, initialValue = 3, step = -1)
        val events = recordEvents(counter)
        counter.start(scheduler)
        scheduler.tick()
        scheduler.tick()
        assertEquals(
            listOf<Counter.Event>(
                Counter.Event.Started(3),
                Counter.Event.Tick(2),
                Counter.Event.Tick(1),
            ), events
        )
        assertEquals(1, counter.get())
    }

    @Test
    fun `调用方可在 Tick 处理器内 stop--停止条件由调用方决定`() {
        val counter = Counter(50.milliseconds, initialValue = 2, step = -1)
        val events = recordEvents(counter)
        counter.on {
            on<Counter.Event.Tick> { if (value == 0L) counter.stop() }
        }
        counter.start(scheduler)
        scheduler.tick()
        scheduler.tick()
        assertEquals(
            listOf<Counter.Event>(
                Counter.Event.Started(2),
                Counter.Event.Tick(1),
                Counter.Event.Tick(0),
                Counter.Event.Stopped(0),
            ), events
        )
        assertFalse(counter.isRunning)
        assertEquals(0, scheduler.activeCount, "Tick 处理器内 stop 必须取消调度任务")
        scheduler.tick()
        assertEquals(4, events.size, "停止后不得再有任何事件")
    }

    @Test
    fun `外部 stop 发 Stopped 且取消任务`() {
        val counter = Counter(50.milliseconds, initialValue = 5, step = -1)
        val events = recordEvents(counter)
        counter.start(scheduler)
        scheduler.tick()
        counter.stop()
        assertEquals(Counter.Event.Stopped(4), events.last())
        assertFalse(counter.isRunning)
        assertEquals(0, scheduler.activeCount)
    }

    @Test
    fun `未启动时 stop 静默--不发虚假 Stopped`() {
        val counter = Counter(50.milliseconds)
        val events = recordEvents(counter)
        counter.stop()
        counter.stop()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `重复 start 幂等--只调度一个任务`() {
        val counter = Counter(50.milliseconds)
        val events = recordEvents(counter)
        counter.start(scheduler)
        counter.start(scheduler)
        assertEquals(1, scheduler.activeCount)
        assertEquals(1, events.count { it is Counter.Event.Started })
    }

    @Test
    fun `stop 后可重新 start--完整生命周期可循环`() {
        val counter = Counter(50.milliseconds, initialValue = 5, step = -1)
        val events = recordEvents(counter)
        counter.start(scheduler)
        counter.stop()
        counter.reset()
        counter.start(scheduler)
        scheduler.tick()
        assertTrue(counter.isRunning)
        assertEquals(Counter.Event.Tick(4), events.last())
    }
}
