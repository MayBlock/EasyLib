package com.github.mayblock.easylib.base.api.scheduler

fun interface TaskExecutor {
    fun execute(task: () -> Unit)

    companion object {
        val Direct = TaskExecutor { it() }
    }
}