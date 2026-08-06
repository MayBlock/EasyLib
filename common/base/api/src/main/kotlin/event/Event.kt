package com.github.mayblock.easylib.base.api.event

import com.github.mayblock.easylib.base.api.util.Disposable
import com.github.mayblock.easylib.base.api.util.Priority

typealias EventHandler<T> = T.() -> Unit

@EventDsl
interface Event {
    interface Cancellable {
        var isCancelled: Boolean
    }
}

class EventListener<T : Event>(
    val type: Class<out T>,
    val group: String?,
    val handler: EventHandler<T>,
    val priority: Priority
)

/**
 * 事件总线的「订阅侧」：只能订阅/退订，不能 emit。
 * 想对外暴露「可被监听、但不可被外部触发」的事件源时使用本接口。
 */
interface EventSource<E : Event> {
    fun <T : E> subscribe(listener: EventListener<T>)
    fun <T : E> unsubscribe(listener: EventListener<T>): Boolean
    fun unsubscribeGroup(group: String): Boolean
    fun unsubscribeAll()
}

interface EventBus<E : Event> : EventSource<E> {
    @Throws(EventException::class)
    fun emit(event: E)
}

@DslMarker
private annotation class EventDsl

@EventDsl
class EventScope<E : Event>(val group: String?, val bus: EventSource<in E>) : Disposable {
    @PublishedApi
    internal val disposables = mutableListOf<Disposable>()
    inline fun <reified T : E> on(priority: Priority = Priority.DEFAULT, noinline handler: EventHandler<T>): EventListener<T> {
        return EventListener(T::class.java, group, handler, priority)
            .also {
                bus.subscribe(it)
                disposables.add { bus.unsubscribe(it) }
            }
    }

    override fun dispose() {
        disposables.forEach { it.dispose() }
    }
}

inline fun <reified T : Event> EventSource<in T>.on(group: String? = null, block: EventScope<T>.() -> Unit): Disposable {
    return EventScope(group, this).apply(block)
}

open class EventException(val event: Event, message: String, cause: Throwable? = null) : Exception(message, cause)
