package com.github.mayblock.easylib.base.impl.bukkit.scheduler

import com.github.mayblock.easylib.api.bukkit.scheduler.BukkitTaskExecutors
import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

class BukkitTaskExecutorsImpl internal constructor(plugin: Plugin): BukkitTaskExecutors {

    override val sync = SyncExecutor(plugin)
    override val async = AsyncExecutor(plugin)

    class SyncExecutor(private val plugin: Plugin) : TaskExecutor {
        override fun execute(task: () -> Unit) {
            if (Bukkit.isPrimaryThread()) task()
            else Bukkit.getScheduler().runTask(plugin, Runnable { task() })
        }
    }

    class AsyncExecutor(private val plugin: Plugin) : TaskExecutor {
        override fun execute(task: () -> Unit) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { task() })
        }
    }
}