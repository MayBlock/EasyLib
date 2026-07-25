package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.item
import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.onAction
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.overlay.builder.OverlaySlotBuilder
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlaySlotBuilderTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test
    fun `onAction 以密封父类型收集为 handler`() {
        val spec = OverlaySlotBuilder().apply {
            item(Material.STONE)
            onAction { }
        }.build()
        assertEquals(
            listOf<Class<*>>(OverlaySlotActionEvent::class.java),
            spec.handlers.map { it.type },
        )
    }

    @Test
    fun `onUpdate 收集为 updateRule`() {
        val spec = OverlaySlotBuilder().apply {
            item(Material.STONE)
            onUpdate(trigger = TaskScheduler.Trigger.Once) { }
        }.build()
        assertEquals(1, spec.updateRules.size)
        assertEquals(0, spec.handlers.size)
    }

    @Test
    fun `build 透传初始物品`() {
        val spec = OverlaySlotBuilder().apply {
            item(Material.DIAMOND, 3)
        }.build()
        assertEquals(Material.DIAMOND, spec.item.type)
        assertEquals(3, spec.item.amount)
    }
}
