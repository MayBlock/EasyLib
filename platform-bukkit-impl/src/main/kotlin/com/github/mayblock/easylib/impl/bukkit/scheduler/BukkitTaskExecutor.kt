package com.github.mayblock.easylib.impl.bukkit.scheduler

import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

class BukkitSyncExecutor(private val plugin: Plugin) : TaskExecutor {

    override fun execute(task: () -> Unit) {
        if (Bukkit.isPrimaryThread()) task()
        else Bukkit.getScheduler().runTask(plugin, Runnable { task() })
    }
}

class BukkitAsyncExecutor(private val plugin: Plugin) : TaskExecutor {

    override fun execute(task: () -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { task() })
    }
}