package com.github.mayblock.easylib.impl.bukkit

import com.github.mayblock.easylib.api.bukkit.BukkitDispatcher
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.lang.Runnable
import kotlin.coroutines.CoroutineContext

class BukkitDispatcherImpl(val plugin: Plugin) : BukkitDispatcher {

    @OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override val sync: CoroutineDispatcher = object : CoroutineDispatcher(), Delay {

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!context.isActive) return
            if (Bukkit.isPrimaryThread()) block.run() else Bukkit.getScheduler().runTask(plugin, block)
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