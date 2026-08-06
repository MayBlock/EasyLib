package com.github.mayblock.easylib.base.api

import com.github.mayblock.easylib.base.api.command.CommandRegistry
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler

interface EasyLibApi {

    val commandRegistry: CommandRegistry
    val taskScheduler: TaskScheduler

    companion object {
        lateinit var api: EasyLibApi
    }
}