package com.github.mayblock.easylib.platform.bukkit.impl.scheduler

import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import org.bukkit.plugin.Plugin

class BukkitExecutionContexts(private val plugin: Plugin) {

    val sync = object : BukkitExecutionContext.Sync {
        override val taskExecutor = BukkitTaskExecutors(plugin).SyncExecutor()
        override val dispatcher = BukkitCoroutineDispatchers(plugin).SyncDispatcher()
    }

    val async = object : BukkitExecutionContext.Async {
        override val taskExecutor = BukkitTaskExecutors(plugin).AsyncExecutor()
        override val dispatcher = BukkitCoroutineDispatchers(plugin).AsyncDispatcher()
    }
}