package com.github.mayblock.easylib.base.impl.event

import com.github.mayblock.easylib.base.api.event.Event
import com.github.mayblock.easylib.base.api.event.EventBus
import com.github.mayblock.easylib.base.api.event.EventException
import com.github.mayblock.easylib.base.api.event.EventListener
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

class SimpleEventBus<E : Event>(
    listeners: List<EventListener<E>> = emptyList(),
) : EventBus<E> {

    companion object {
        private val logger = LoggerFactory.getLogger(SimpleEventBus::class.java)
    }

    // CopyOnWriteArrayList: 读多写少场景下保证线程安全，emit 迭代的是快照，无需加锁也不会 ConcurrentModificationException。
    // 列表按 priority 升序维护，emit 时无需再排序。
    private val listeners = CopyOnWriteArrayList<EventListener<out E>>().also {
        it.addAll(listeners)
    }

    @Synchronized
    override fun <T : E> subscribe(listener: EventListener<T>) {
        listeners.add(listener)
        // CopyOnWriteArrayList.sort 是原子操作（整体替换底层数组），订阅是低频操作，开销可接受。
        listeners.sortBy { it.priority }
    }

    override fun <T : E> unsubscribe(listener: EventListener<T>): Boolean =
        listeners.remove(listener)

    override fun unsubscribeGroup(group: String): Boolean =
        listeners.removeIf { it.group == group }

    override fun unsubscribeAll() = listeners.clear()

    override fun emit(event: E) {
        // listeners 已按 priority 有序，直接顺序触发即可。
        listeners.forEach { listener ->
            if (!listener.type.isInstance(event)) return@forEach
            try {
                @Suppress("UNCHECKED_CAST")
                (listener as EventListener<E>).handler(event)
            } catch (e: Exception) {
                // 单个监听器异常不应中断其余监听器，记录日志后继续。
                val msg = "Exception while handling event ${event::class.java.name} in listener (group=${listener.group})"
                logger.error(msg, EventException(event, msg, e))
            }
        }
    }
}
