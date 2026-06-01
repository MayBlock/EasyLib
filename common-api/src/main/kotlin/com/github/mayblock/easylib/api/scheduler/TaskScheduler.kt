package com.github.mayblock.easylib.api.scheduler

import kotlin.time.Duration

interface TaskScheduler {

    val tickPeriod: Duration

    fun scheduleTask(task: Task): Int
    fun cancelTask(taskId: Int): Boolean
    fun cancelAllTasks()

    fun scheduleTask(builder: TaskBuilder.() -> Unit): Int {
        return TaskBuilder()
            .apply(builder)
            .build()
            .let(::scheduleTask)
    }

    interface Task {
        val onTick: (() -> Unit)?
        val onAsyncTick: (() -> Unit)?
    }

    @DslMarker
    annotation class TaskDsl

    @TaskDsl
    class TaskBuilder {
        var onTick: (() -> Unit)? = null
        var onAsyncTick: (() -> Unit)? = null

        internal fun build(): Task = object : Task {
            override val onTick = this@TaskBuilder.onTick
            override val onAsyncTick = this@TaskBuilder.onAsyncTick
        }
    }
}