package com.github.mayblock.easylib.impl.util

import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

class Counter(
    private val interval: Duration,
    private val initialValue: Long = 0,
    private val step: Long = 1,
    private val notifyExecutor: TaskExecutor = TaskExecutor.Direct
) {
    private val _isRunning = AtomicBoolean(false)
    val isRunning get() = _isRunning.get()

    private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()
    private val counter = AtomicLong(initialValue)
    private var disposableRef = AtomicReference<Disposable?>(null)

    fun get() = counter.get()
    fun set(value: Long) = counter.set(value)
    fun reset() = counter.set(initialValue)

    fun addListener(block: (Long) -> Unit) {
        listeners.add(block)
    }

    fun removeListener(block: (Long) -> Unit) = listeners.remove(block)

    fun start(scheduler: TaskScheduler) {
        val placeholder = Disposable { }
        if (!disposableRef.compareAndSet(null, placeholder)) return
        _isRunning.set(true)
        val taskId = scheduler.scheduleTask(TaskScheduler.Trigger.Interval(interval), notifyExecutor) {
            val count = counter.addAndGet(step)
            listeners.forEach { it(count) }
        }
        disposableRef.set(Disposable { scheduler.cancelTask(taskId) })

    }

    fun stop() {
        _isRunning.set(false)
        disposableRef.getAndSet(null)?.dispose()
    }
}