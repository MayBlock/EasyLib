package com.github.mayblock.easylib.impl.bukkit.game.arena.feature

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.feature.Feature
import com.github.mayblock.easylib.api.feature.FeatureKey
import com.github.mayblock.easylib.api.game.arena.event.ArenaJoinedEvent
import com.github.mayblock.easylib.api.game.arena.event.ArenaLeaveEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.game.arena.bridge.BridgeEvent
import com.github.mayblock.easylib.impl.bukkit.util.*
import com.github.mayblock.easylib.impl.util.Counter
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.entity.Player
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class WaitingLobbyFeature<T>(
    private val minPlayers: Int,
    private val maxPlayers: Int,
    private val playerCount: () -> Int,
    private val isActive: () -> Boolean,
    private val startCountdown: Duration,
    onComplete: () -> Unit,
    private val counter: Counter = Counter(1.ticks, startCountdown.toTicks(), -1),
) : Feature<T> where T : BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>, T : TaskScheduler {

    companion object Key : FeatureKey<WaitingLobbyFeature<*>>("WaitingLobbyFeature")

    private lateinit var arena: T
    private val onlinePlayers get() = arena.players.mapNotNull { it.bukkitPlayer }
    private val playerStatus get() = "${playerCount()}/$maxPlayers"
    private var offListeners: Disposable? = null

    init {
        counter.addListener { n ->
            if (n == 0L) {
                onComplete(onComplete)
                counter.stop()
                counter.reset()
                return@addListener
            }
            onCountdown(n)
        }
    }

    override fun onInstall(context: T) {
        arena = context
        offListeners = context.on {
            on<BridgeEvent.EntityDamageEvent> {
                if (!isActive()) return@on
                isCancelled = true
            }
            on<ArenaJoinedEvent> {
                if (!isActive()) return@on
                (player as BukkitArenaPlayer).bukkitPlayer?.gameMode = GameMode.ADVENTURE
                if (!counter.isRunning && playerCount() >= minPlayers) {
                    counter.start(context)
                }
            }
            on<ArenaLeaveEvent> {
                (player as BukkitArenaPlayer).bukkitPlayer?.let { player ->
                    player.gameMode = player.previousGameMode ?: GameMode.SURVIVAL
                }
                if (counter.isRunning && playerCount() < minPlayers) {
                    onlinePlayers.sendMessage("当前人数不足，需要等待更多玩家！") {
                        it.resetCountdownHud()
                    }
                    counter.stop()
                }
            }
        }
    }

    override fun onUninstall(context: T) {
        offListeners?.dispose()
    }

    private fun onComplete(onComplete: () -> Unit) {
        onlinePlayers.forEach { player ->
            player.gameMode = player.previousGameMode ?: GameMode.SURVIVAL
            player.resetCountdownHud()
        }
        onComplete()
    }

    private fun onCountdown(n: Long) {
        if (counter.isRunning) {
            onlinePlayers.forEach { it.updateReadyHud(n.ticks) }
        } else {
            onlinePlayers.sendActionBar("等待中 ($playerStatus)")
        }
    }

    // 倒计时用 level/exp 借位显示进度条；完成或中止时必须复位，否则玩家的经验条/等级 HUD 会残留倒计时数字。
    private fun Player.resetCountdownHud() {
        this.sendPackets {
            forPlayer {
                setExperience(exp, level, totalExperience)
            }
        }
    }

    private fun Player.updateReadyHud(remaining: Duration) {
        val remainingSeconds = ceil(remaining.toDouble(DurationUnit.SECONDS)).toInt()
        this.sendPackets {
            forPlayer {
                setExperience((remaining / startCountdown).toFloat(), remainingSeconds, totalExperience)
            }
        }
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
}