package com.github.mayblock.easylib.api.scheduler

import kotlin.time.Duration

interface TaskScheduler {

    fun scheduleTask(task: Task): Int
    fun cancelTask(taskId: Int): Boolean
    fun cancelAllTasks()

    fun scheduleTask(
        trigger: Trigger = Trigger.Once,
        executor: TaskExecutor = TaskExecutor.Direct,
        block: () -> Unit
    ): Int {
        return object : Task {
            override val trigger: Trigger = trigger
            override val executor: TaskExecutor = executor
            override val onTick: () -> Unit = block
        }.let(::scheduleTask)
    }

    interface Task {
        val trigger: Trigger
        val executor: TaskExecutor
        val onTick: () -> Unit
    }

    /** 触发器为值语义（data）：参数相同的触发器相等，调度方可据此把同触发器的任务归组。 */
    sealed interface Trigger {
        data object Once : Trigger
        data class Delay(val delay: Duration) : Trigger
        data class Interval(val period: Duration) : Trigger
    }
}