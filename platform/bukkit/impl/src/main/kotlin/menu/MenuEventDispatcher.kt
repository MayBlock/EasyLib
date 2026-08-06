package com.github.mayblock.easylib.base.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.base.api.event.EventBus
import com.github.mayblock.easylib.base.api.event.EventListener
import com.github.mayblock.easylib.base.api.event.EventSource
import com.github.mayblock.easylib.base.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.base.impl.event.SimpleEventBus

/**
 * 菜单事件面（对标 [com.github.mayblock.easylib.base.impl.bukkit.overlay.OverlayEventDispatcher]）：
 * 总线持有、按槽过滤的 handler 接线。对外只暴露订阅侧（[EventSource]）；emit 只能通过 [publish]。
 *
 * 没有 overlay 那个 `publishOnMainThread`：menu 事件全部产生于主线程（Bukkit 事件回调本身
 * 就跑在主线程），不像 overlay 那样有来自 netty 包处理线程的事件需要转发。
 */
internal class MenuEventDispatcher(
    private val bus: EventBus<MenuEvent> = SimpleEventBus(),
) : EventSource<MenuEvent> by bus {

    /** 把每个槽声明的点击处理器，作为「按 index 过滤」的监听挂到菜单总线上。 */
    fun wireSlotHandlers(specs: Map<Int, SlotSpec>) {
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

    fun publish(event: MenuEvent) = bus.emit(event)

    fun close() = bus.unsubscribeAll()
}
