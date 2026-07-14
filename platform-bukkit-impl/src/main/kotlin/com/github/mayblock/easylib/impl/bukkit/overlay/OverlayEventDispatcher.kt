package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
import com.github.mayblock.easylib.api.event.EventBus
import com.github.mayblock.easylib.api.event.EventListener
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.event.SimpleEventBus

/**
 * 覆盖层事件面：总线持有、按槽过滤的 handler 接线、主线程派发策略。
 * 对外只暴露订阅侧（[EventSource]）；emit 只能通过 [publish]/[publishOnMainThread]。
 */
internal class OverlayEventDispatcher(
    private val scheduler: TaskScheduler,
    private val bus: EventBus<OverlayEvent> = SimpleEventBus(),
) : EventSource<OverlayEvent> by bus {

    /** 把每个槽声明的点击/交互处理器，作为「按 index 过滤」的监听挂到覆盖层总线上（现 AbstractPlayerOverlay.init 的逻辑）。 */
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

    /** 主线程同步派发（show/hide 等本就发生在主线程的路径）。 */
    fun publish(event: OverlayEvent) = bus.emit(event)

    /**
     * 把覆盖层事件派发调度到主线程执行（`publish` 最终会跑到玩家侧的处理器代码，
     * 后者按约定运行在主线程；本方法自身在 netty 包处理线程调用，故需转发）。
     * resync 发包不受影响，仍在 netty 线程原地执行（归 [OverlayTransport] 管）。
     */
    fun publishOnMainThread(event: OverlayEvent) {
        scheduler.scheduleTask { onTick = { publish(event) } }
    }

    fun close() = bus.unsubscribeAll()
}
