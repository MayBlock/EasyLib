package com.github.mayblock.easylib.impl.game.arena.feature

import com.github.mayblock.easylib.api.feature.Feature
import com.github.mayblock.easylib.api.feature.FeatureKey
import com.github.mayblock.easylib.api.game.arena.Arena
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import java.util.concurrent.atomic.AtomicLong

open class PreGameCountdownFeature<T>(
    protected val requiredPlayers: Int,
    protected val playerCount: () -> Int,
    protected val startCountdown: Long,
    protected val isActive: () -> Boolean,
    protected open val onCountdownTick: (State, Long) -> Unit = { _, _ -> },
    protected open val onStateChanged: (State, State) -> Unit = { _, _ -> },
    protected open val onComplete: () -> Unit = {}
) : Feature<T> where T : Arena<*, *>, T : TaskScheduler {

    companion object Key : FeatureKey<PreGameCountdownFeature<*>>("PreGameCountdownFeature")

    private var taskId = -1

    override fun onInstall(context: T) {
        taskId = CountdownTask(
            requiredPlayers,
            playerCount,
            startCountdown,
            isActive,
            onCountdownTick,
            onStateChanged,
            onComplete
        ).let(context::scheduleTask)
    }

    override fun onUninstall(context: T) {
        context.cancelTask(taskId)
    }

    enum class State {
        WAITING,
        READY
    }
}

private class CountdownTask(
    private val requiredPlayers: Int,
    private val playerCount: () -> Int,
    startCountdown: Long,
    private val isActive: () -> Boolean,
    private val onCountdownTick: (PreGameCountdownFeature.State, Long) -> Unit,
    private val onStateChanged: (PreGameCountdownFeature.State, PreGameCountdownFeature.State) -> Unit,
    private val onComplete: () -> Unit
) : TaskScheduler.Task {

    private var state = PreGameCountdownFeature.State.WAITING
        set(value) {
            if (field != value) {
                field = value
                onStateChanged(field, value)
            }
        }

    private val initialCountdown = startCountdown
    private val timer = AtomicLong(initialCountdown)

    override val onAsyncTick = {}
    override val onTick = onTick@{
        if (!isActive()) return@onTick
        if (state != PreGameCountdownFeature.State.WAITING && state != PreGameCountdownFeature.State.READY) {
            return@onTick
        }
        state = if (playerCount() < requiredPlayers) {
            timer.set(initialCountdown)
            PreGameCountdownFeature.State.WAITING
        } else {
            PreGameCountdownFeature.State.READY
        }
        val countdown = if (state == PreGameCountdownFeature.State.READY) {
            timer.decrementAndGet().also { remaining ->
                if (remaining <= 0) {
                    onComplete()
                    state = PreGameCountdownFeature.State.WAITING
                    return@onTick
                }
            }
        } else timer.get()
        onCountdownTick(state, countdown)
    }
}