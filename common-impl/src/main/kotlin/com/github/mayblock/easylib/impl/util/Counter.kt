package com.github.mayblock.easylib.impl.util

import com.github.mayblock.easylib.api.event.EventBus
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import com.github.mayblock.easylib.impl.util.Counter.Event
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * 周期计数器：按 [interval] 周期以 [step] 步进，向订阅方发布 [Event]。
 * 何时停止完全由调用方决定（在事件处理器或外部逻辑中调用 [stop]），Counter 自身不内置停止条件。
 *
 * 事件契约：
 * - [Event.Started]：start() 成功启动时发出，携带启动时的真实计数值；保证先于首个 [Event.Tick]。
 * - [Event.Tick]：每周期步进后发出，携带步进后的值。
 * - [Event.Stopped]：由 [stop] 停止时发出；未运行时 stop() 静默、不发事件。
 *
 * 线程安全：start / stop 经内部锁互斥，可从任意线程调用。
 * 注意：[Event.Started] 在锁内发出，Started 处理器内不得回调本对象的 start/stop——
 * 锁为 monitor 可重入，违反不会死锁，但此时 stop() 会因运行句柄尚未赋值而被静默吞掉、
 * [isRunning] 亦读到 false，表现为"停不下来"而非报错。
 * 并发调度器下 stop() 无法打断已在执行中的那一次 tick，[Event.Stopped] 之后可能残留一个迟到
 * [Event.Tick]；主线程型调度器（Bukkit）不受影响。
 */
class Counter(
    private val interval: Duration,
    private val initialValue: Long = 0,
    private val step: Long = 1,
    private val notifyExecutor: TaskExecutor = TaskExecutor.Direct,
    private val eventBus: EventBus<Event> = SimpleEventBus(),
) : EventSource<Event> by eventBus {

    private val lock = Any()

    /** 唯一的运行状态：非 null 即运行中。由 [lock] 保护，[isRunning] 从它推导，杜绝双状态失步。 */
    private var disposable: Disposable? = null

    val isRunning: Boolean get() = synchronized(lock) { disposable != null }

    private val counter = AtomicLong(initialValue)

    fun get() = counter.get()
    fun set(value: Long) = counter.set(value)
    fun reset() = counter.set(initialValue)

    fun start(scheduler: TaskScheduler) {
        synchronized(lock) {
            if (disposable != null) return
            // 调度前发 Started：保证 Started ≺ 首个 Tick（立即触发型调度器下调度后发会失序）。
            eventBus.emit(Event.Started(counter.get()))
            val taskId = scheduler.scheduleTask(
                TaskScheduler.Trigger.Interval(interval), notifyExecutor
            ) {
                eventBus.emit(Event.Tick(counter.addAndGet(step)))
            }
            // 与 scheduleTask 同临界区：stop() 不可能再插进"任务已跑、句柄未存"的窗口。
            disposable = Disposable { scheduler.cancelTask(taskId) }
        }
    }

    fun stop() {
        // 锁内原子认领释放权（防重复 stop 双发事件），锁外 emit（不持锁执行用户代码）。
        val released = synchronized(lock) {
            disposable?.also {
                it.dispose()
                disposable = null
            } != null
        }
        if (released) eventBus.emit(Event.Stopped(counter.get()))
    }

    sealed interface Event : com.github.mayblock.easylib.api.event.Event {
        data class Tick(val value: Long) : Event
        data class Started(val initialValue: Long) : Event
        data class Stopped(val finalValue: Long) : Event
    }
}
