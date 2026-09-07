package com.github.mayblock.easylib.platform.bukkit.impl.testing

import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class TestSyncContext(
    override val taskExecutor: TaskExecutor = TaskExecutor.Direct,
    override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : BukkitExecutionContext.Sync

class TestAsyncContext(
    override val taskExecutor: TaskExecutor = TaskExecutor.Direct,
    override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : BukkitExecutionContext.Async
