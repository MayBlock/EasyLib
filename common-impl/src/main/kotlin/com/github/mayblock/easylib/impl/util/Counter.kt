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
 *
 * 事件契约：
 * - [Event.Started]：start() 成功启动时发出，携带启动时的真实计数值；保证先于首个 [Event.Tick]。
 * - [Event.Tick]：每周期步进后发出，携带步进后的值。
 * - [Event.Completed]：计数到达 [stopTarget] 时自动停止并发出（仅当 stopTarget 非 null）；与 Stopped 互斥。
 * - [Event.Stopped]：仅由外部 [stop] 中止时发出；未运行时 stop() 静默、不发事件。
 *
 * 线程安全：start / stop / 自动完成经内部锁互斥，可从任意线程调用。
 * 注意：[Event.Started] 在锁内发出，Started 处理器内不得回调本对象的 start/stop。
 */
class Counter(
    private val interval: Duration,
    private val initialValue: Long = 0,
    private val step: Long = 1,
    private val notifyExecutor: TaskExecutor = TaskExecutor.Direct,
    private val eventBus: EventBus<Event> = SimpleEventBus(),
    private val stopTarget: Long? = null,
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
            ) { onTick() }
            // 与 scheduleTask 同临界区：stop() 不可能再插进"任务已跑、句柄未存"的窗口。
            disposable = Disposable { scheduler.cancelTask(taskId) }
        }
    }

    fun stop() {
        if (tryRelease()) eventBus.emit(Event.Stopped(counter.get()))
    }

    private fun onTick() {
        val value = counter.addAndGet(step)
        eventBus.emit(Event.Tick(value))
        // value == stopTarget：Long 与 Long? 比较，stopTarget 为 null 时恒 false。
        if (value == stopTarget && tryRelease()) {
            eventBus.emit(Event.Completed(value))
        }
    }

    /**
     * 原子地认领并释放运行状态。返回是否由本次调用完成释放——
     * 外部 stop 与自动完成共用此认领，保证 Stopped/Completed 恰好只发一个。
     */
    private fun tryRelease(): Boolean = synchronized(lock) {
        disposable?.also {
            it.dispose()
            disposable = null
        } != null
    }

    sealed interface Event : com.github.mayblock.easylib.api.event.Event {
        data class Tick(val value: Long) : Event
        data class Started(val initialValue: Long) : Event
        data class Stopped(val finalValue: Long) : Event
        data class Completed(val finalValue: Long) : Event
    }
}
