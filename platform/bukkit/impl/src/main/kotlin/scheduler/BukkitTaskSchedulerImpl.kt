package com.github.mayblock.easylib.platform.bukkit.impl.scheduler

import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.platform.bukkit.impl.util.toTicks
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BukkitTaskScheduler(
    internal val plugin: Plugin
) : TaskScheduler {

    private val idGenerator = AtomicInteger(0)
    private val tasks = ConcurrentHashMap<Int, BukkitTask>()

    /**
     * 占位任务：一次性任务在真正调度前先把 id 登记进 [tasks]（值为本占位），
     * 防止调度极快完成时（尤其是异步任务、或 delay=0 的同步任务）任务本体在另一线程上
     * 跑完并自删，而调度调用还没来得及把 `Bukkit.getScheduler().runTask...()` 返回的真实
     * [BukkitTask] 写回 `tasks[id]`——修复前的写法正是「先调度、再赋值」，一旦这个时间窗口
     * 被跑穿，`tasks[id]` 就会在任务已完成之后才被写入一条永远不会再被清理的死记录（内存泄漏）。
     */
    private val placeholder = object : BukkitTask {
        override fun getOwner() = plugin
        override fun getTaskId() = -1
        override fun isSync() = true
        override fun isCancelled() = false
        override fun cancel() {}
    }

    private inner class BukkitTaskScope(private val taskId: Int): TaskScheduler.TaskScope {
        override fun cancel() {
            cancelTask(taskId)
        }
    }

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = idGenerator.getAndIncrement()
        val trigger = task.trigger
        val taskScope = BukkitTaskScope(id)
        val isOneShot = trigger is TaskScheduler.Trigger.Once || trigger is TaskScheduler.Trigger.Delay
        // Bukkit 调度器只负责「何时触发」，触发后把回调交给 Task.executor 决定「在哪个线程跑」：
        // Direct/sync 在主线程就地执行；async 会再转投到异步线程。
        val repeatingRunnable = Runnable {
            task.executor.execute { task.onTick(taskScope) }
        }
        val oneShotRunnable = Runnable {
            task.executor.execute { task.onTick(taskScope) }
            // 自删而非 cancelTask(id)：任务已经跑完（或已移交给 executor），没有底层 BukkitTask
            // 需要再 cancel()，直接把 map 里的记录（占位或真实引用）摘掉即可。
            tasks.remove(id)
        }
        if (isOneShot) {
            tasks[id] = placeholder
        }
        val bukkitTask = when (trigger) {
            TaskScheduler.Trigger.Once ->
                Bukkit.getScheduler().runTask(plugin, oneShotRunnable)

            is TaskScheduler.Trigger.Delay ->
                Bukkit.getScheduler().runTaskLater(plugin, oneShotRunnable, trigger.delay.toTicks())

            is TaskScheduler.Trigger.Interval ->
                Bukkit.getScheduler().runTaskTimer(plugin, repeatingRunnable, 0, trigger.period.toTicks())
        }
        if (isOneShot) {
            // 只有占位仍在（任务还没跑完自删）时才回填真实引用；
            // 若占位已经被自删，说明任务已经完成，不能让这条记录死而复生。
            tasks.computeIfPresent(id) { _, _ -> bukkitTask }
        } else {
            tasks[id] = bukkitTask
        }
        return id
    }

    override fun cancelTask(taskId: Int): Boolean {
        val task = tasks.remove(taskId) ?: return false
        task.cancel()
        return true
    }

    override fun cancelAllTasks() {
        tasks.values.forEach { it.cancel() }
        tasks.clear()
    }
}