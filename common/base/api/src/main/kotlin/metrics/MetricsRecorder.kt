package com.github.mayblock.easylib.base.api.metrics

interface MetricsRecorder {
    fun <T> record(name: String, block: () -> T): T
}