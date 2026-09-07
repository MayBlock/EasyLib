package com.github.mayblock.easylib.platform.bukkit.impl.overlay

import com.github.mayblock.easylib.base.api.event.EventBus
import com.github.mayblock.easylib.base.api.event.EventListener
import com.github.mayblock.easylib.base.api.event.EventSource
import com.github.mayblock.easylib.base.impl.event.SimpleEventBus
import com.github.mayblock.easylib.platform.bukkit.api.overlay.OverlayEvent
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.OverlaySlotSpec

/**
 * 覆盖层事件面：总线持有和按槽过滤的 handler 接线；执行上下文由覆盖层统一管理。
 * 对外只暴露订阅侧（[EventSource]）；emit 只能通过 [publish]。
 */
internal class OverlayEventDispatcher(
    private val bus: EventBus<OverlayEvent> = SimpleEventBus(),
) : EventSource<OverlayEvent> by bus {

    /** 把每个槽声明的点击/交互处理器，作为「按 index 过滤」的监听挂到覆盖层总线上。 */
    fun wireSlotHandlers(specs: Map<Int, OverlaySlotSpec>) {
        specs.forEach { (index, spec) ->
            spec.handlers.forEach { handler ->
                bus.subscribe(
                    EventListener(
                        handler.type,
                        null,
                        { if (index == this.index) handler.block(this) },
                        handler.priority,
                    )
                )
            }
        }
    }

    fun publish(event: OverlayEvent) = bus.emit(event)
    fun close() = bus.unsubscribeAll()
}
