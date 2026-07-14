package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlayEvent
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlayShowEvent
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.event.EventListener
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import io.mockk.mockk
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayEventTest {

    private val overlay = mockk<PlayerOverlay>()
    private val player = mockk<Player>()

    @Test
    fun `Click 与 Interact 子类暴露 index 与各自字段`() {
        val click = OverlaySlotActionEvent.Click(overlay, 3, player, ClickType.LEFT)
        val interact = OverlaySlotActionEvent.Interact(overlay, 4, player, OverlaySlotActionEvent.Interact.Action.RIGHT_CLICK)
        assertEquals(3, click.index)
        assertEquals(ClickType.LEFT, click.clickType)
        assertEquals(4, interact.index)
        assertEquals(OverlaySlotActionEvent.Interact.Action.RIGHT_CLICK, interact.action)
    }

    @Test
    fun `密封父类 when 可区分来源`() {
        val events: List<OverlaySlotActionEvent> = listOf(
            OverlaySlotActionEvent.Click(overlay, 3, player, ClickType.LEFT),
            OverlaySlotActionEvent.Interact(overlay, 4, player, OverlaySlotActionEvent.Interact.Action.LEFT_CLICK),
        )
        val kinds = events.map {
            when (it) {
                is OverlaySlotActionEvent.Click -> "click:${it.index}"
                is OverlaySlotActionEvent.Interact -> "interact:${it.index}"
            }
        }
        assertEquals(listOf("click:3", "interact:4"), kinds)
    }

    @Test
    fun `SimpleEventBus 按密封父类型统一派发两种来源`() {
        val bus = SimpleEventBus<OverlayEvent>()
        val seen = mutableListOf<Int>()
        bus.subscribe(
            EventListener<OverlaySlotEvent>(
                OverlaySlotActionEvent::class.java, null, { seen += index }, Priority.DEFAULT,
            )
        )
        bus.emit(OverlaySlotActionEvent.Click(overlay, 3, player, ClickType.LEFT))
        bus.emit(OverlaySlotActionEvent.Interact(overlay, 4, player, OverlaySlotActionEvent.Interact.Action.LEFT_CLICK))
        bus.emit(OverlayShowEvent(overlay, player)) // 非槽位操作事件不匹配
        assertEquals(listOf(3, 4), seen)
    }
}
