package com.github.mayblock.easylib.api.event

import com.github.mayblock.easylib.api.util.Disposable

typealias EventHandler<T> = T.() -> Unit

@EventDsl
interface Event {
    interface Cancellable {
        var isCancelled: Boolean
    }
}

class EventListener<T : Event>(
    val type: Class<T>,
    val group: String?,
    val handler: EventHandler<T>,
    val priority: Int = 10
)

interface EventBus<E : Event> {
    fun <T : E> subscribe(listener: EventListener<T>)
    fun <T : E> unsubscribe(listener: EventListener<T>): Boolean
    fun unsubscribeGroup(group: String): Boolean

    @Throws(EventException::class)
    fun emit(event: E)
}

@DslMarker
annotation class EventDsl

@EventDsl
class EventScope<E : Event>(val group: String?, val bus: EventBus<in E>) : Disposable {
    @PublishedApi
    internal val disposables = mutableListOf<Disposable>()
    inline fun <reified T : E> on(priority: Int = 10, noinline handler: EventHandler<T>): EventListener<T> {
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

inline fun <reified T : Event> EventBus<in T>.on(group: String? = null, block: EventScope<T>.() -> Unit): Disposable {
    return EventScope(group, this).apply(block)
}

open class EventException(val event: Event, message: String, cause: Throwable? = null) : Exception(message, cause)