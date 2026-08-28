package com.github.mayblock.easylib.platform.bukkit.impl.game.arena.feature

import com.github.mayblock.easylib.base.api.feature.Feature
import com.github.mayblock.easylib.base.api.feature.FeatureKey
import com.github.mayblock.easylib.platform.bukkit.api.game.arena.BukkitArena
import com.github.mayblock.easylib.platform.bukkit.api.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.platform.bukkit.api.game.arena.BukkitArenaPlayer
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin

sealed class PlayerJoinLeaveFeature<T : BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>>(
    protected val plugin: Plugin,
    protected val onJoin: Player.() -> Unit,
    protected val onQuit: BukkitArenaPlayer.() -> Unit,
    protected val onRejoin: BukkitArenaPlayer.() -> Unit
) : Feature<T> {

    class Server<T : BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>>(
        plugin: Plugin,
        onJoin: Player.() -> Unit,
        onQuit: BukkitArenaPlayer.() -> Unit,
        onRejoin: BukkitArenaPlayer.() -> Unit = {}
    ) : PlayerJoinLeaveFeature<T>(plugin, onJoin, onQuit, onRejoin), Listener {

        companion object Key : FeatureKey<Server<*>>("JoinHandlerFeature/Server")

        private lateinit var arena: T
        override fun onInstall(context: T) {
            arena = context
            Bukkit.getPluginManager().registerEvents(this, plugin)
        }

        override fun onUninstall(context: T) {
            HandlerList.unregisterAll(this)
        }

        @EventHandler
        private fun onJoin(e: PlayerJoinEvent) {
            val player = e.player
            arena.getPlayer(player.uniqueId)?.let {
                onRejoin(it)
            } ?: run {
                onJoin(player)
            }
        }

        @EventHandler
        private fun onLeave(e: PlayerQuitEvent) {
            arena.getPlayer(e.player.uniqueId)?.let { onQuit(it) }
        }
    }

    class SingleWorld<T : BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>>(
        private val world: World,
        plugin: Plugin,
        onJoin: Player.() -> Unit,
        onQuit: BukkitArenaPlayer.() -> Unit,
        onRejoin: BukkitArenaPlayer.() -> Unit = {}
    ) : PlayerJoinLeaveFeature<T>(plugin, onJoin, onQuit, onRejoin), Listener {

        companion object Key : FeatureKey<SingleWorld<*>>("JoinHandlerFeature/SingleWorld")

        private lateinit var arena: T
        override fun onInstall(context: T) {
            arena = context
            Bukkit.getPluginManager().registerEvents(this, plugin)
        }

        override fun onUninstall(context: T) {
            HandlerList.unregisterAll(this)
        }

        @EventHandler
        private fun onWorldChange(e: PlayerChangedWorldEvent) {
            val player = e.player
            val arenaPlayer = arena.getPlayer(player.uniqueId)
            if (player.world.key == world.key) {
                arenaPlayer?.let {
                    onRejoin(it)
                } ?: run {
                    onJoin(player)
                }
            } else if (e.from.key == world.key) {
                arenaPlayer?.let { onQuit(it) }
            }
        }

        // 登录时若玩家已经在目标世界（例如服务器重启后原地重登），PlayerChangedWorldEvent 不会触发，
        // 必须单独监听 PlayerJoinEvent/PlayerQuitEvent 才能覆盖这两种边界情况。
        @EventHandler
        private fun onJoin(e: PlayerJoinEvent) {
            val player = e.player
            if (player.world.key != world.key) return
            arena.getPlayer(player.uniqueId)?.let {
                onRejoin(it)
            } ?: run {
                onJoin(player)
            }
        }

        @EventHandler
        private fun onQuit(e: PlayerQuitEvent) {
            val player = e.player
            if (player.world.key != world.key) return
            arena.getPlayer(player.uniqueId)?.let { onQuit(it) }
        }
    }
}