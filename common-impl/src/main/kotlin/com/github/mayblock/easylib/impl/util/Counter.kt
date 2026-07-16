package com.github.mayblock.easylib.impl.util

import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

class Counter(
    private val interval: Duration,
    private val scheduler: TaskScheduler,
    private val initialValue: Int = 0,
    private val step: Int = 1,
    private val notifyExecutor: TaskExecutor = TaskExecutor.Direct
) {

    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val counter = AtomicInteger(initialValue)
    private var disposableRef = AtomicReference<Disposable?>(null)

    fun get() = counter.get()
    fun set(value: Int) = counter.set(value)
    fun reset() = counter.set(initialValue)

    fun addListener(block: (Int) -> Unit) {
        listeners.add(block)
    }

    fun removeListener(block: (Int) -> Unit) = listeners.remove(block)

    fun start() {
        val placeholder = Disposable { }
        if (!disposableRef.compareAndSet(null, placeholder)) return
        val taskId = scheduler.scheduleTask(TaskScheduler.Trigger.Interval(interval), notifyExecutor) {
            val count = counter.addAndGet(step)
            listeners.forEach { it(count) }
        }
        disposableRef.set(Disposable { scheduler.cancelTask(taskId) })
    }

    fun stop() {
        disposableRef.getAndSet(null)?.dispose()
    }
}