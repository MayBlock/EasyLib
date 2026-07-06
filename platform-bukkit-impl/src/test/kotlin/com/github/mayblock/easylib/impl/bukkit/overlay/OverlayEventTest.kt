package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlaySlotEvent
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
    fun `click 与 interact 事件暴露 index 与各自字段`() {
        val click = OverlayClickEvent(overlay, 3, player, ClickType.LEFT)
        val interact = OverlayInteractEvent(overlay, 4, player, OverlayInteractEvent.Action.RIGHT_CLICK)
        assertEquals(3, click.index)
        assertEquals(ClickType.LEFT, click.type)
        assertEquals(4, interact.index)
        assertEquals(OverlayInteractEvent.Action.RIGHT_CLICK, interact.action)
    }

    @Test
    fun `SimpleEventBus 按事件类型过滤派发 OverlaySlotEvent`() {
        val bus = SimpleEventBus<OverlayEvent>()
        val clicks = mutableListOf<Int>()
        bus.subscribe(
            EventListener<OverlaySlotEvent>(
                OverlayClickEvent::class.java, null, { clicks += index }, Priority.DEFAULT,
            )
        )
        bus.emit(OverlayClickEvent(overlay, 3, player, ClickType.LEFT))
        bus.emit(OverlayInteractEvent(overlay, 4, player, OverlayInteractEvent.Action.LEFT_CLICK))
        assertEquals(listOf(3), clicks) // interact 不匹配 click 监听
    }
}
