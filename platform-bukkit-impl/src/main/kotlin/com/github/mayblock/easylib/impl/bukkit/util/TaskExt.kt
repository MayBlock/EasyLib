package com.github.mayblock.easylib.impl.bukkit.util

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * 将时长换算为 Minecraft 的 tick 数（1 tick = 50ms）。
 *
 * 不足 1 tick 的时长（含大于 0 但换算后为 0 的情况）会被 [coerceAtLeast] 修正为最少按 1 tick 执行，
 * 避免 0 tick 的调度间隔导致任务被立即/高频触发甚至死循环。
 */
fun Duration.toTicks(): Long = (this.inWholeMilliseconds / 50).coerceAtLeast(1)
val Long.ticks
    get() = (this * 50)
        .toDuration(DurationUnit.MILLISECONDS)