package com.github.mayblock.easylib.api.bukkit.overlay.dsl

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority

/**
 * 覆盖层单槽 DSL：仅展示 + 交互，**无 movable/placeable、无 take/place**。
 * 背包窗口内点击 → [onClick]（[OverlayClickEvent]）；手持挥动/使用 → [onInteract]（[OverlayInteractEvent]）；
 * 定时刷新 → [onUpdate]（[OverlayUpdateEvent]，仍异步）。
 */
@PlayerOverlayDsl
interface OverlaySlotScope {
    fun onClick(priority: Priority = Priority.DEFAULT, block: OverlayClickEvent.() -> Unit)
    fun onInteract(priority: Priority = Priority.DEFAULT, block: OverlayInteractEvent.() -> Unit)
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: OverlayUpdateEvent.() -> Unit)
}
