package com.github.mayblock.easylib.impl.bukkit.game.arena.feature

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.feature.Feature
import com.github.mayblock.easylib.api.feature.FeatureKey
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.util.scheduleAsyncTask
import fr.mrmicky.fastboard.FastBoard
import net.md_5.bungee.api.ChatColor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface ScoreboardProvider<Player : BukkitArenaPlayer> {

    fun accepts(context: Player): Boolean
    fun title(context: Player): String
    fun lines(context: Player): List<String>
    val priority: Priority
}

class ScoreboardFeature<A, Player : BukkitArenaPlayer> private constructor(
    private val period: Duration,
    private val providers: List<ScoreboardProvider<Player>>
) : Feature<A> where A : BukkitArena<Player, out BukkitArenaEntity>, A : TaskScheduler {

    constructor(period: Duration = 1.seconds, block: Builder<Player>.() -> Unit)
            : this(period, Builder<Player>().apply(block).toList())

    companion object Key : FeatureKey<ScoreboardFeature<*, *>>("ScoreboardFeature")

    private var taskId: Int? = null
    private val fastboardCache = mutableMapOf<BukkitArenaPlayer, FastBoard>()

    override fun onInstall(context: A) {
        taskId = context.scheduleAsyncTask(TaskScheduler.Trigger.Interval(period)) {
            refresh(context)
        }
    }

    override fun onUninstall(context: A) {
        taskId?.let(context::cancelTask)
        fastboardCache.values.forEach { it.delete() }
        fastboardCache.clear()
    }

    private fun refresh(arena: A) {
        if (arena.players.isEmpty()) return
        val gone = fastboardCache.keys.filter { it !in arena.players }
        gone.forEach { fastboardCache.remove(it)?.delete() }
        arena.players.filter { it.bukkitPlayer?.isOnline == true }.forEach { player ->
            val provider = providers.filter { it.accepts(player) }.maxByOrNull { it.priority }
            if (provider == null) return@forEach
            val scoreboard = fastboardCache.getOrPut(player) {
                val bukkitPlayer = player.bukkitPlayer ?: return@forEach
                FastBoard(bukkitPlayer)
            }
            scoreboard.updateTitle(provider.title(player).let {
                ChatColor.translateAlternateColorCodes('&', it)
            })
            scoreboard.updateLines(provider.lines(player).map {
                ChatColor.translateAlternateColorCodes('&', it)
            })
        }
    }

    @DslMarker
    private annotation class FeatureBuilder

    @FeatureBuilder
    class Builder<Player : BukkitArenaPlayer> internal constructor() : Iterable<ScoreboardProvider<Player>> {
        private val providers = mutableListOf<ScoreboardProvider<Player>>()

        override fun iterator() = providers.iterator()

        fun provider(provider: ScoreboardProvider<Player>) {
            providers.add(provider)
        }

        fun onView(priority: Priority = Priority.DEFAULT, block: ViewBuilder.() -> Unit) {
            ViewBuilder().apply(block).build(priority).also(providers::add)
        }

        @FeatureBuilder
        inner class ViewBuilder internal constructor() {

            private var accepts: (Player.() -> Boolean)? = null
            private var title: (Player.() -> String)? = null
            private var lines: (Player.() -> List<String>)? = null

            fun accepts(block: Player.() -> Boolean) {
                this.accepts = block
            }

            fun title(block: Player.() -> String) {
                this.title = block
            }

            fun lines(block: Player.() -> List<String>) {
                this.lines = block
            }

            internal fun build(priority: Priority): ScoreboardProvider<Player> {
                require(lines != null) { "lines is required" }
                return object : ScoreboardProvider<Player> {
                    override fun accepts(context: Player) = accepts?.invoke(context) ?: true
                    override fun title(context: Player) = title?.invoke(context) ?: ""
                    override fun lines(context: Player) = lines!!.invoke(context)
                    override val priority: Priority = priority
                }
            }
        }
    }
}
