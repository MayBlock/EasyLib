package com.github.mayblock.easylib.impl.bukkit.game.arena.feature

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.feature.Feature
import com.github.mayblock.easylib.api.feature.FeatureKey
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import fr.mrmicky.fastboard.FastBoard
import net.md_5.bungee.api.ChatColor

interface ScoreboardProvider<Player : BukkitArenaPlayer> {

    fun accepts(context: Player): Boolean
    fun title(context: Player): String
    fun lines(context: Player): List<String>
    val priority: Int get() = 0
}

class ScoreboardFeature<A, Player : BukkitArenaPlayer> private constructor(
    private val providers: List<ScoreboardProvider<Player>>
) : Feature<A> where A : BukkitArena<Player, out BukkitArenaEntity>, A : TaskScheduler {

    constructor(block: Builder<Player>.() -> Unit) : this(Builder<Player>().apply(block).toList())

    companion object Key : FeatureKey<ScoreboardFeature<*, *>>("ScoreboardFeature")

    private var taskId: Int? = null
    private val fastboardCache = mutableMapOf<BukkitArenaPlayer, FastBoard>()

    override fun onInstall(context: A) {
        taskId = context.scheduleTask {
            onAsyncTick = {
                refresh(context)
            }
        }
    }

    override fun onUninstall(context: A) {
        taskId?.let(context::cancelTask)
        fastboardCache.clear()
    }

    private fun refresh(arena: A) {
        if (arena.players.isEmpty()) return
        arena.players.filter { it.bukkitPlayer?.isOnline == true }.forEach { player ->
            val provider = providers.filter { it.accepts(player) }.maxByOrNull { it.priority }
            if (provider == null) return@forEach
            val scoreboard = fastboardCache.getOrPut(player) { FastBoard(player.bukkitPlayer) }
            scoreboard.updateTitle(provider.title(player).let {
                ChatColor.translateAlternateColorCodes('&', it)
            })
            scoreboard.updateLines(provider.lines(player).map {
                ChatColor.translateAlternateColorCodes('&', it)
            })
        }
    }

    @DslMarker
    annotation class FeatureBuilder

    @FeatureBuilder
    class Builder<Player : BukkitArenaPlayer> internal constructor() : Collection<ScoreboardProvider<Player>> {
        private val providers = mutableListOf<ScoreboardProvider<Player>>()

        override fun contains(element: ScoreboardProvider<Player>) = providers.contains(element)
        override fun containsAll(elements: Collection<ScoreboardProvider<Player>>) = providers.addAll(elements)
        override fun isEmpty() = providers.isEmpty()
        override fun iterator() = providers.iterator()
        override val size: Int get() = providers.size

        fun provider(provider: ScoreboardProvider<Player>) {
            providers.add(provider)
        }

        fun onView(block: ViewBuilder.() -> Unit) {
            ViewBuilder().apply(block).build().also(providers::add)
        }

        @FeatureBuilder
        inner class ViewBuilder internal constructor() {

            private var accepts: (Player.() -> Boolean)? = null
            private var title: (Player.() -> String)? = null
            private var lines: (Player.() -> List<String>)? = null
            private var priority: Int = 0

            fun accepts(block: Player.() -> Boolean) {
                this.accepts = block
            }

            fun title(block: Player.() -> String) {
                this.title = block
            }

            fun lines(block: Player.() -> List<String>) {
                this.lines = block
            }

            fun priority(priority: Int) {
                this.priority = priority
            }

            internal fun build(): ScoreboardProvider<Player> {
                require(lines != null) { "lines is required" }
                return object : ScoreboardProvider<Player> {
                    override fun accepts(context: Player) = accepts?.invoke(context) ?: true
                    override fun title(context: Player) = title?.invoke(context) ?: ""
                    override fun lines(context: Player) = lines!!.invoke(context)
                    override val priority: Int = this@ViewBuilder.priority
                }
            }
        }
    }
}
