package com.github.mayblock.easylib.base.api.metrics

interface MetricsRecorder {
    fun <T> record(name: String, block: () -> T): T

    /**
     * 协程版计量。
     *
     * 默认实现**不计量**，只执行 [block]。需要协程侧指标的实现类必须覆写本方法——
     * 本接口的同步版 [record] 收的是普通 lambda，无法在不阻塞线程的前提下包裹挂起块。
     */
    suspend fun <T> recordSuspending(name: String, block: suspend () -> T): T = block()
}
