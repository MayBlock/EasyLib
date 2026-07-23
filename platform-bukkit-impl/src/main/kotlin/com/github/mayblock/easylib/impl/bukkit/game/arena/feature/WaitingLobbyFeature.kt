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
    private val onComplete: () -> Unit,
    private val counter: Counter = Counter(
        interval = 1.ticks,
        initialValue = startCountdown.toTicks(),
        step = -1,
    ),
) : Feature<T> where T : BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>, T : TaskScheduler {

    companion object Key : FeatureKey<WaitingLobbyFeature<*>>("WaitingLobbyFeature")

    private lateinit var arena: T
    private val onlinePlayers get() = arena.players.mapNotNull { it.bukkitPlayer }
    private val playerStatus get() = "${playerCount()}/$maxPlayers"
    private var disposer: Disposable? = null

    /**
     * 完成/中止分流标志：Tick 数到 0 时置位后再 stop()，Stopped 处理器据此区分
     * 「正常完成」与「外部中止」。Counter 是通用节拍器，停止条件由本 Feature 决定；
     * 若无此标志，正常开赛也会走中止分支误发"倒计时终止"（即修复前的 bug #2）。
     */
    private var completing = false

    init {
        counter.on {
            on<Counter.Event.Started> {
                onlinePlayers.sendMessage("游戏即将开始！")
            }
            on<Counter.Event.Tick> {
                if (value == 0L) {
                    completing = true   // 到 0 属正常完成：置位后停表，善后交给 Stopped 处理器分流
                    counter.stop()
                    return@on
                }
                val remaining = value.ticks
                onlinePlayers.forEach { it.updateCountdownHud(remaining) }
                broadcastCountdownTitle(remaining)   // 广播与 per-player 平级，只发一份
            }
            // 复位与善后集中在唯一终态处理器：Leave/uninstall 只负责"决定停"。
            on<Counter.Event.Stopped> {
                counter.reset()
                if (completing) {
                    completing = false
                    completeCountdown()
                    return@on
                }
                val msg = if (playerCount() < minPlayers) {
                    "当前人数不足，需要等待更多玩家！"
                } else "倒计时终止"
                onlinePlayers.sendMessage(msg) {
                    it.resetCountdownHud()
                }
            }
        }
    }

    override fun onInstall(context: T) {
        arena = context
        val subscription = context.on {
            on<BridgeEvent.EntityDamageEvent> {
                if (!isActive()) return@on
                isCancelled = true
            }
            on<ArenaJoinedEvent> {
                if (!isActive()) return@on
                (player as BukkitArenaPlayer).bukkitPlayer?.gameMode = GameMode.ADVENTURE
                tryStartCountdown()
            }
            on<ArenaLeaveEvent> {
                if (!isActive()) return@on
                (player as BukkitArenaPlayer).bukkitPlayer?.let { p ->
                    p.gameMode = p.previousGameMode ?: GameMode.SURVIVAL
                }
                // ArenaLeaveEvent 在移除之后发出，playerCount() 已不含离开者，直接比较无差一。
                if (counter.isRunning && playerCount() < minPlayers) counter.stop()
            }
        }
        val checker = context.scheduleTask(TaskScheduler.Trigger.Interval(1.seconds)) {
            if (!isActive() || counter.isRunning) return@scheduleTask
            tryStartCountdown()   // 兜底：玩家先于安装到齐（或 join 早于 install）时补启动
            if (!counter.isRunning) onlinePlayers.sendActionBar("等待中 ($playerStatus)")
        }
        disposer = Disposable {
            subscription.dispose()
            context.cancelTask(checker)
        }
    }

    override fun onUninstall(context: T) {
        disposer?.dispose()
        disposer = null
        counter.stop()
    }

    /** 启动条件的唯一出处：join 事件（即时）与 checker（兜底）共用。 */
    private fun tryStartCountdown() {
        if (!counter.isRunning && playerCount() >= minPlayers) counter.start(arena)
    }

    private fun completeCountdown() {
        onlinePlayers.forEach { player ->
            player.gameMode = player.previousGameMode ?: GameMode.SURVIVAL
            player.resetCountdownHud()
        }
        onComplete()
    }

    // 倒计时用 level/exp 借位显示进度条；完成或中止时必须复位，否则玩家的经验条/等级 HUD 会残留倒计时数字。
    private fun Player.resetCountdownHud() {
        this.sendPackets {
            forPlayer {
                setExperience(exp, level, totalExperience)
            }
        }
    }

    private fun Player.updateCountdownHud(remaining: Duration) {
        val remainingSeconds = ceil(remaining.toDouble(DurationUnit.SECONDS)).toInt()
        this.sendPackets {
            forPlayer {
                setExperience((remaining / startCountdown).toFloat(), remainingSeconds, totalExperience)
            }
        }
        this.sendActionBar("${remainingSeconds}s 即将开始！ ($playerStatus)")
    }

    private fun broadcastCountdownTitle(remaining: Duration) {
        if (remaining.inWholeMilliseconds % 1000L != 0L) return   // 只在整秒边界触发
        val color = when (remaining.inWholeSeconds) {
            30L, 20L, 10L -> ChatColor.GREEN
            in 3L..5L -> ChatColor.YELLOW
            1L, 2L -> ChatColor.RED
            else -> return
        }
        val title = "$color${ChatColor.BOLD}${remaining.inWholeSeconds}"
        onlinePlayers.forEach { player ->
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            player.sendTitle(title, null, 0, 20, 0)
        }
    }
}
