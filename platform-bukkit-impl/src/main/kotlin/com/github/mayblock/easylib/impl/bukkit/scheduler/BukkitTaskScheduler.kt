package com.github.mayblock.easylib.impl.bukkit.scheduler

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.util.toTicks
import com.google.common.primitives.Longs.max
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
    private val tasks = ConcurrentHashMap<Int, BukkitTask>()

    private val bukkitTickPeriod = (tickPeriod.toLong(DurationUnit.MILLISECONDS) / 50)
        .coerceAtLeast(0)

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
                    max(trigger.delay.toTicks(), bukkitTickPeriod)
                )
            } else Bukkit.getScheduler().runTaskLater(
                plugin,
                oneShotRunnable,
                max(trigger.delay.toTicks(), bukkitTickPeriod)
            )

            is TaskScheduler.Trigger.Interval -> if (task.isAsync) {
                Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin,
                    repeatingRunnable,
                    0,
                    max(trigger.period.toTicks(), bukkitTickPeriod)
                )
            } else Bukkit.getScheduler().runTaskTimer(
                plugin,
                repeatingRunnable,
                0,
                max(trigger.period.toTicks(), bukkitTickPeriod)
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