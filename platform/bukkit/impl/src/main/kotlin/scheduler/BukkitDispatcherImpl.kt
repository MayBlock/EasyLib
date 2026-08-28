package com.github.mayblock.easylib.platform.bukkit.impl.scheduler

import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitDispatcher
import com.github.mayblock.easylib.platform.bukkit.impl.util.toTicks
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.lang.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

class BukkitDispatcherImpl(val plugin: Plugin) : BukkitDispatcher {

    @OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override val sync: CoroutineDispatcher = object : CoroutineDispatcher(), Delay {

        // 用协程官方的“跳过 dispatch”机制替代原先「dispatch 内就地 run」的写法：
        // 已经在主线程上时无需再排队一次 tick，直接返回 false 让协程机器就地继续执行。
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!context.isActive) return
            Bukkit.getScheduler().runTask(plugin, block)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val task = Bukkit.getScheduler()
                .runTaskLater(
                    plugin,
                    Runnable { continuation.apply { resumeUndispatched(Unit) } },
                    timeMillis.milliseconds.toTicks()
                )
            continuation.invokeOnCancellation { task.cancel() }
        }

    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override val async: CoroutineDispatcher = object : CoroutineDispatcher(), Delay {

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!context.isActive) return
            Bukkit.getScheduler().runTaskAsynchronously(plugin, block)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val task = Bukkit.getScheduler().runTaskLaterAsynchronously(
                plugin,
                Runnable { continuation.apply { resumeUndispatched(Unit) } },
                timeMillis.milliseconds.toTicks()
            )
            continuation.invokeOnCancellation { task.cancel() }
        }
    }
}