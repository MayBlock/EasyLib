package com.github.mayblock.easylib.impl.bukkit.scheduler

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

class BukkitTaskScheduler(
    private val plugin: Plugin,
    override val tickPeriod: Duration = 50.milliseconds // 20tick/sec
) : TaskScheduler {

    private val idGenerator = AtomicInteger(0)
    private val tasks = ConcurrentHashMap<Int, com.github.mayblock.easylib.api.scheduler.TaskScheduler.Task>()
    private val scheduler = TaskScheduler()

    private val bukkitTickPeriod = (tickPeriod.toLong(DurationUnit.MILLISECONDS) / 50)
        .coerceAtLeast(0)

    override fun scheduleTask(task: com.github.mayblock.easylib.api.scheduler.TaskScheduler.Task): Int {
        val id = idGenerator.getAndIncrement()
        tasks[id] = task
        if (!scheduler.isEnabled) {
            scheduler.start()
        }
        return id
    }

    override fun cancelTask(taskId: Int): Boolean {
        val removed = tasks.remove(taskId) != null
        if (removed && tasks.isEmpty()) {
            scheduler.stop()
        }
        return removed
    }

    override fun cancelAllTasks() {
        scheduler.stop()
        tasks.clear()
    }

    private inner class TaskScheduler {
        private var syncTask: BukkitTask? = null
        private var asyncTask: BukkitTask? = null

        val isEnabled get() = syncTask != null || asyncTask != null

        fun start() {
            if (syncTask == null) {
                syncTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                    tasks.values.toList().forEach { it.onTick?.invoke() }
                }, 0, bukkitTickPeriod)
            }
            if (asyncTask == null) {
                asyncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
                    tasks.values.toList().forEach { it.onAsyncTick?.invoke() }
                }, 0, bukkitTickPeriod)
            }
        }

        fun stop() {
            syncTask?.cancel()?.let {
                syncTask = null
            }
            asyncTask?.cancel()?.let {
                asyncTask = null
            }
        }
    }
}