package com.github.mayblock.easylib.base.impl.metrics

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder

object NoOpMetricsRecorder : MetricsRecorder {
    override fun <T> record(name: String, block: () -> T): T = block()
}