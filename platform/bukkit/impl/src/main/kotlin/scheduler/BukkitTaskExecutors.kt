package com.github.mayblock.easylib.platform.bukkit.impl.scheduler

import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

class BukkitTaskExecutors internal constructor(private val plugin: Plugin) {

    inner class SyncExecutor internal constructor(): TaskExecutor {
        override fun execute(task: () -> Unit) {
            if (Bukkit.isPrimaryThread()) task()
            else Bukkit.getScheduler().runTask(plugin, Runnable { task() })
        }
    }

    inner class AsyncExecutor internal constructor(): TaskExecutor {
        override fun execute(task: () -> Unit) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { task() })
        }
    }
}