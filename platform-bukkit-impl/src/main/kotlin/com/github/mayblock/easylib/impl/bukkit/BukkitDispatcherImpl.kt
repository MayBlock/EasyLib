package com.github.mayblock.easylib.impl.bukkit

import com.github.mayblock.easylib.api.bukkit.BukkitDispatcher
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.slf4j.LoggerFactory
import java.lang.Runnable
import kotlin.coroutines.CoroutineContext

class BukkitDispatcherImpl(val plugin: Plugin) : BukkitDispatcher {

    private val logger by lazy { LoggerFactory.getLogger(BukkitDispatcherImpl::class.java) }

    @OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override val sync: CoroutineDispatcher = object : CoroutineDispatcher(), Delay {

        // 用协程官方的“跳过 dispatch”机制替代原先「dispatch 内就地 run」的写法：
        // 已经在主线程上时无需再排队一次 tick，直接返回 false 让协程机器就地继续执行。
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!context.isActive) return
            if (!plugin.isEnabled) {
                // 插件已禁用时再调 Bukkit.getScheduler().runTask 会抛 IllegalPluginAccessException，
                // 且这个异常发生在调度器内部、协程的调用方根本看不到——等价于协程静默挂死。
                // 降级为当前线程直接跑：能完成的尽量完成，并 warn 提示这属于非正常路径。
                logger.warn(
                    "Plugin ${plugin.name} is disabled; running a sync-dispatched coroutine continuation " +
                        "inline on the current thread instead of scheduling it onto the main thread."
                )
                block.run()
                return
            }
            Bukkit.getScheduler().runTask(plugin, block)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val task = Bukkit.getScheduler()
                .runTaskLater(
                    plugin,
                    Runnable { continuation.apply { resumeUndispatched(Unit) } },
                    timeMillis / 50
                )
            continuation.invokeOnCancellation { task.cancel() }
        }

    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override val async: CoroutineDispatcher = object : CoroutineDispatcher(), Delay {

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!context.isActive) return
            if (!plugin.isEnabled) {
                logger.warn(
                    "Plugin ${plugin.name} is disabled; running an async-dispatched coroutine continuation " +
                        "inline on the current thread instead of scheduling it asynchronously."
                )
                block.run()
                return
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, block)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val task = Bukkit.getScheduler().runTaskLaterAsynchronously(
                plugin,
                Runnable { continuation.apply { resumeUndispatched(Unit) } },
                timeMillis / 50
            )
            continuation.invokeOnCancellation { task.cancel() }
        }
    }
}