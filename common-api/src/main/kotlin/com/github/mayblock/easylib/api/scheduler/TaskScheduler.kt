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
        val trigger: Trigger
        val isAsync: Boolean
        val onTick: () -> Unit
    }

    sealed interface Trigger {
        object Once : Trigger
        class Delay(val delay: Duration) : Trigger
        class Interval(val period: Duration) : Trigger
    }

    @DslMarker
    annotation class TaskDsl

    @TaskDsl
    class TaskBuilder {
        var trigger: Trigger = Trigger.Once
        var isAsync: Boolean = false
        var onTick: (() -> Unit)? = null

        internal fun build(): Task {
            require(onTick != null) { "onTick can not be null." }
            return object : Task {
                override val trigger = this@TaskBuilder.trigger
                override val isAsync = this@TaskBuilder.isAsync
                override val onTick = this@TaskBuilder.onTick!!
            }
        }
    }
}