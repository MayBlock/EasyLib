package com.github.mayblock.easylib.impl.bukkit.scheduler

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.util.toTicks
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BukkitTaskScheduler(
    private val plugin: Plugin
) : TaskScheduler {

    private val idGenerator = AtomicInteger(0)
    private val tasks = ConcurrentHashMap<Int, BukkitTask>()

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = idGenerator.getAndIncrement()
        val trigger = task.trigger
        val repeatingRunnable = Runnable {
            task.onTick()
        }
        val oneShotRunnable = Runnable {
            task.onTick()
            cancelTask(id)
        }
        tasks[id] = when (trigger) {
            TaskScheduler.Trigger.Once -> if (task.isAsync) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, oneShotRunnable)
            } else Bukkit.getScheduler().runTask(plugin, oneShotRunnable)

            is TaskScheduler.Trigger.Delay -> if (task.isAsync) {
                Bukkit.getScheduler().runTaskLaterAsynchronously(
                    plugin,
                    oneShotRunnable,
                    trigger.delay.toTicks()
                )
            } else Bukkit.getScheduler().runTaskLater(
                plugin,
                oneShotRunnable,
                trigger.delay.toTicks()
            )

            is TaskScheduler.Trigger.Interval -> if (task.isAsync) {
                Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin,
                    repeatingRunnable,
                    0,
                    trigger.period.toTicks()
                )
            } else Bukkit.getScheduler().runTaskTimer(
                plugin,
                repeatingRunnable,
                0,
                trigger.period.toTicks()
            )
        }
        return id
    }

    override fun cancelTask(taskId: Int): Boolean {
        val removed = tasks.remove(taskId)?.also {
            it.cancel()
        } != null
        return removed
    }

    override fun cancelAllTasks() {
        tasks.values.forEach { it.cancel() }
        tasks.clear()
    }
}