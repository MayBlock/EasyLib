package com.github.mayblock.easylib.platform.bukkit.impl.util

import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.scheduleTask
import com.github.mayblock.easylib.platform.bukkit.impl.testing.TestAsyncContext
import com.github.mayblock.easylib.platform.bukkit.impl.testing.TestSyncContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

private class RecordingScheduler : TaskScheduler {
    val tasks = mutableListOf<TaskScheduler.Task>()

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        tasks += task
        return tasks.lastIndex
    }

    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() = Unit
}

class TaskExtTest {

    @Test
    fun `execution contexts retain scheduler triggers and select executors`() {
        val scheduler = RecordingScheduler()
        val sync = TestSyncContext(TaskExecutor { it() })
        val async = TestAsyncContext(TaskExecutor { it() })
        val interval = TaskScheduler.Trigger.Interval(2.seconds)

        val syncId = scheduler.scheduleTask(context = sync) { }
        val asyncId = scheduler.scheduleTask(interval, async) { cancel() }

        assertEquals(2, scheduler.tasks.size)
        assertEquals(0, syncId)
        assertEquals(1, asyncId)
        assertSame(sync.taskExecutor, scheduler.tasks[0].executor)
        assertSame(async.taskExecutor, scheduler.tasks[1].executor)
        assertEquals(TaskScheduler.Trigger.Once, scheduler.tasks[0].trigger)
        assertEquals(interval, scheduler.tasks[1].trigger)
        var cancelled = false
        scheduler.tasks[1].onTick(object : TaskScheduler.TaskScope {
            override fun cancel() { cancelled = true }
        })
        assertEquals(true, cancelled)
    }
}
