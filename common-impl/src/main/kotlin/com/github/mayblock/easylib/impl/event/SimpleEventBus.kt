package com.github.mayblock.easylib.impl.event

import com.github.mayblock.easylib.api.event.Event
import com.github.mayblock.easylib.api.event.EventBus
import com.github.mayblock.easylib.api.event.EventException
import com.github.mayblock.easylib.api.event.EventListener
import org.slf4j.LoggerFactory

class SimpleEventBus<E : Event> : EventBus<E> {

    companion object {
        private val logger = LoggerFactory.getLogger(SimpleEventBus::class.java)
    }

    private val listeners = mutableListOf<EventListener<out E>>()

    override fun <T : E> subscribe(listener: EventListener<T>) {
        listeners.add(listener)
    }

    override fun <T : E> unsubscribe(listener: EventListener<T>): Boolean =
        listeners.remove(listener)

    override fun unsubscribeGroup(group: String): Boolean =
        listeners.removeIf { it.group == group }

    override fun emit(event: E) {
        listeners
            .filter { it.type.isInstance(event) }
            .sortedBy { it.priority }
            .forEach { listener ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    (listener as EventListener<E>).handler(event)
                } catch (e: Exception) {
                    throw EventException(event, "Exception posting event ${event::class.java.name}", e)
                }
            }
    }

    fun unsubscribeAll() = listeners.clear()
}
