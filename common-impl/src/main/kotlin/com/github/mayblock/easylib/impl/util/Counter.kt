package com.github.mayblock.easylib.impl.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

class Counter(
    private val interval: Duration,
    private val scope: CoroutineScope,
    private val initialValue: Int = 0,
    private val type: Type = Type.INCREMENT,
) {

    enum class Type {
        INCREMENT,
        DECREMENT,
    }

    private val listeners = mutableListOf<suspend (Int, Controller) -> Unit>()
    private val counter = AtomicInteger(initialValue)
    private val controller = Controller(this)
    private var isActive = false

    inner class Controller(val counter: Counter) {
        fun stop() {
            isActive = false
        }

        fun set(newValue: Int) {
            counter.counter.set(newValue)
        }

        fun reset() {
            counter.counter.set(initialValue)
        }
    }

    fun get() = counter.get()

    fun addListener(block: suspend (Int, Controller) -> Unit) {
        listeners.add(block)
    }

    fun start(): Controller {
        isActive = true
        scope.launch {
            while (isActive) {
                val count = when (type) {
                    Type.INCREMENT -> {
                        counter.incrementAndGet()
                    }

                    Type.DECREMENT -> {
                        counter.decrementAndGet()
                    }
                }
                listeners.forEach { block ->
                    block(count, controller)
                }
                delay(interval)
            }
        }
        return Controller(this)
    }
}