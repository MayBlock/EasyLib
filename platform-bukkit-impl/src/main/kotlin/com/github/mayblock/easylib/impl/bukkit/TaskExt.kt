package com.github.mayblock.easylib.impl.bukkit

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

fun Duration.toTicks(): Long = (this.inWholeMilliseconds / 50)
val Long.ticks
    get() = (this * 50)
        .toDuration(DurationUnit.MILLISECONDS)