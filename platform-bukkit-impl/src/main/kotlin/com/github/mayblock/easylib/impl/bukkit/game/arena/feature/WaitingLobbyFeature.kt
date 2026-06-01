package com.github.mayblock.easylib.impl.bukkit.game.arena.feature

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.feature.FeatureKey
import com.github.mayblock.easylib.api.game.arena.event.ArenaJoinedEvent
import com.github.mayblock.easylib.api.game.arena.event.ArenaLeaveEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.game.arena.bridge.BridgeEvent
import com.github.mayblock.easylib.impl.bukkit.sendActionBar
import com.github.mayblock.easylib.impl.bukkit.setProgressbar
import com.github.mayblock.easylib.impl.bukkit.ticks
import com.github.mayblock.easylib.impl.bukkit.toTicks
import com.github.mayblock.easylib.impl.game.arena.feature.PreGameCountdownFeature
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.entity.Player
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class WaitingLobbyFeature<T>(
    minPlayers: Int,
    private val maxPlayers: Int,
    playerCount: () -> Int,
    isActive: () -> Boolean,
    countdownDuration: Duration,
    onComplete: () -> Unit,
) : PreGameCountdownFeature<T>(
    requiredPlayers = minPlayers,
    playerCount = playerCount,
    startCountdown = countdownDuration.toTicks(),
    isActive = isActive
) where T : BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>, T : TaskScheduler {

    companion object Key : FeatureKey<WaitingLobbyFeature<*>>("WaitingLobbyFeature")

    private lateinit var arena: T
    private val onlinePlayers get() = arena.players.mapNotNull { it.bukkitPlayer }
    private val playerStatus get() = "${playerCount()}/$maxPlayers"

    override val onCountdownTick: (State, Long) -> Unit = { state, ticks ->
        handleCountdownTick(state, ticks.ticks)
    }

    override val onStateChanged: (State, State) -> Unit = ::handleStateChange

    override val onComplete: () -> Unit = {
        onlinePlayers.forEach { player ->
            player.gameMode = player.previousGameMode ?: GameMode.SURVIVAL
        }
        onComplete()
    }

    private fun handleCountdownTick(state: State, remaining: Duration) {
        when (state) {
            State.WAITING -> onlinePlayers.sendActionBar("等待中 ($playerStatus)")
            State.READY -> onlinePlayers.forEach { it.updateReadyHud(remaining) }
        }
    }

    private fun handleStateChange(oldState: State, newState: State) {
        val countdownAborted = oldState == State.READY && newState == State.WAITING
        if (countdownAborted) {
            onlinePlayers.forEach { it.sendMessage("当前人数不足，需要等待更多玩家！") }
        }
    }

    private fun Player.updateReadyHud(remaining: Duration) {
        val remainingSeconds = ceil(remaining.toDouble(DurationUnit.SECONDS)).toInt()
        this.level = remainingSeconds
        this.setProgressbar(remaining.toTicks().toFloat() / startCountdown)
        this.sendActionBar("${remainingSeconds}s 即将开始！ ($playerStatus)")
        broadcastCountdownTitle(remaining)
    }

    private fun broadcastCountdownTitle(remaining: Duration) {
        val color = when (remaining) {
            30.seconds, 20.seconds, 10.seconds -> ChatColor.GREEN
            5.seconds, 4.seconds, 3.seconds -> ChatColor.YELLOW
            2.seconds, 1.seconds -> ChatColor.RED
            else -> return
        }
        val title = "${color}${ChatColor.BOLD}${remaining.toInt(DurationUnit.SECONDS)}"
        onlinePlayers.forEach { player ->
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            player.sendTitle(title, null, 0, 20, 0)
        }
    }

    private var offListeners: Disposable? = null

    override fun onInstall(context: T) {
        arena = context
        offListeners = context.on {
            on<BridgeEvent.EntityDamageEvent> {
                if (isActive()) {
                    isCancelled = true
                }
            }
            on<ArenaJoinedEvent> {
                (player as BukkitArenaPlayer).bukkitPlayer?.gameMode = GameMode.ADVENTURE
            }
            on<ArenaLeaveEvent> {
                (player as BukkitArenaPlayer).bukkitPlayer?.let { player ->
                    player.gameMode = player.previousGameMode ?: GameMode.SURVIVAL
                }
            }
        }
        super.onInstall(context)
    }

    override fun onUninstall(context: T) {
        offListeners?.dispose()
        super.onUninstall(context)
    }
}