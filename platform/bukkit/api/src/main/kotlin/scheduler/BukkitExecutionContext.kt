package com.github.mayblock.easylib.platform.bukkit.api.scheduler

import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.scheduler.scheduleTask
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/** 执行上下文的类型键，与持有插件资源的上下文实例分离。 */
sealed interface BukkitExecutionContextKey<T : BukkitExecutionContext>

/** 提供执行能力；持有本对象或传入 Kotlin context 参数不会自动切换当前线程。 */
sealed interface BukkitExecutionContext {

    val taskExecutor: TaskExecutor
    val dispatcher: CoroutineDispatcher

    interface Sync : BukkitExecutionContext {
        companion object Key : BukkitExecutionContextKey<Sync>
    }

    interface Async : BukkitExecutionContext {
        companion object Key : BukkitExecutionContextKey<Async>
    }
}

fun BukkitExecutionContext.execute(block: () -> Unit) = taskExecutor.execute(block)

/** 在指定 dispatcher 执行并挂起等待结果，继承调用方的取消与异常传播语义。 */
suspend fun <R> BukkitExecutionContext.executeCoroutine(block: suspend CoroutineScope.() -> R): R = withContext(dispatcher, block)

fun TaskScheduler.scheduleTask(
    trigger: TaskScheduler.Trigger = TaskScheduler.Trigger.Once,
    context: BukkitExecutionContext,
    block: TaskScheduler.TaskScope.() -> Unit,
): Int = scheduleTask(trigger, context.taskExecutor, block)
