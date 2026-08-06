package com.github.mayblock.easylib.base.api.scheduler

import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler.Task
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler.TaskScope
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler.Trigger
import kotlin.time.Duration

interface TaskScheduler {

    fun scheduleTask(task: Task): Int
    fun cancelTask(taskId: Int): Boolean
    fun cancelAllTasks()

    interface Task {
        val trigger: Trigger
        val executor: TaskExecutor
        val onTick: TaskScope.() -> Unit
    }

    interface TaskScope {
        fun cancel()
    }

    /** 触发器为值语义（data）：参数相同的触发器相等，调度方可据此把同触发器的任务归组。 */
    sealed interface Trigger {
        data object Once : Trigger
        data class Delay(val delay: Duration) : Trigger
        data class Interval(val period: Duration) : Trigger
    }
}

fun TaskScheduler.scheduleTask(
    trigger: Trigger = Trigger.Once,
    executor: TaskExecutor = TaskExecutor.Direct,
    block: TaskScope.() -> Unit
): Int {
    return object : Task {
        override val trigger: Trigger = trigger
        override val executor: TaskExecutor = executor
        override val onTick: TaskScope.() -> Unit = block
    }.let(::scheduleTask)
}