package com.github.mayblock.easylib.api

import com.github.mayblock.easylib.api.command.CommandRegistry
import com.github.mayblock.easylib.api.scheduler.TaskScheduler

interface EasyLibApi {

    val commandRegistry: CommandRegistry
    val taskScheduler: TaskScheduler

    companion object {
        lateinit var api: EasyLibApi
    }
}