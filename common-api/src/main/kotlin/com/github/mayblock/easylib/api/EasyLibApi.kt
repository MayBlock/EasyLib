package com.github.mayblock.easylib.api

import com.github.mayblock.easylib.api.command.*
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import kotlin.time.Duration

interface EasyLibApi {

    val commandRegistry: CommandRegistry
    fun createTaskScheduler(tickPeriod: Duration): TaskScheduler

    companion object {
        lateinit var api: EasyLibApi
    }
}