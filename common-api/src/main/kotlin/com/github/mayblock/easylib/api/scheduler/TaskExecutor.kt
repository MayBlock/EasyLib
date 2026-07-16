package com.github.mayblock.easylib.api.scheduler

fun interface TaskExecutor {
    fun execute(task: () -> Unit)

    companion object {
        val Direct = TaskExecutor { it() }
    }
}