package com.github.mayblock.easylib.api.bukkit.scheduler

import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor

interface BukkitTaskExecutors {

    val sync: TaskExecutor
    val async: TaskExecutor
}