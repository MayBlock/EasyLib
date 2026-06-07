package com.github.mayblock.easylib.impl.bukkit.game.arena.service

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.bukkit.menu.player.PlayerMenuBuilder
import com.github.mayblock.easylib.api.service.Service
import com.github.mayblock.easylib.api.service.ServiceKey
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib.Companion.api
import com.github.mayblock.easylib.impl.bukkit.util.gameMode
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import org.bukkit.GameMode
import org.bukkit.entity.Player
import java.util.*

class SpectatorService<A : BukkitArena<out BukkitArenaPlayer, *>>(
    private val arena: A,
    playerInventory: (PlayerMenuBuilder.() -> Unit)? = null,
) : Service {

    val virtualPlayerInventory = api.menuApi.createPlayerInventoryMenu(playerInventory ?: {})

    companion object Key : ServiceKey<SpectatorService<*>>("SpectatorService")

    private val spectators = mutableListOf<Spectator>()

    override fun onRegister() {}

    override fun onUnregister() {}

    fun getPlayersWithoutSpectator(): List<BukkitArenaPlayer> =
        arena.players - spectators.map { it.arenaPlayer }.toSet()

    fun addSpectator(player: BukkitArenaPlayer) {
        require(spectators.none { it.arenaPlayer == player }) {
            "Player ${player.name} is already spectating"
        }
        Spectator(player).also { it.apply() }.let {
            spectators.add(it)
        }
    }

    fun removeSpectator(player: BukkitArenaPlayer): Boolean =
        spectators.firstOrNull { it.arenaPlayer == player }?.let(::removeSpectator) ?: false

    fun getSpectator(uuid: UUID) = spectators.firstOrNull { it.arenaPlayer.uuid == uuid }
    private fun removeSpectator(spectator: Spectator): Boolean {
        spectator.restore()
        return spectators.remove(spectator)
    }

    inner class Spectator internal constructor(
        val arenaPlayer: BukkitArenaPlayer
    ) {
        var watching: Player? = null
            private set

        internal fun watch(target: Player): Boolean {
            if (!target.isOnline) return false
            val player = arenaPlayer.bukkitPlayer ?: return false
            player.sendPackets {
                forPlayer {
                    camera(target.entityId)
                }
            }
            watching = target
            return true
        }

        internal fun stopWatching() {
            if (watching == null) return
            val player = arenaPlayer.bukkitPlayer
            player?.sendPackets {
                forPlayer {
                    camera(player.entityId)
                }
            }
            watching = null
        }

        fun apply() {
            val player = arenaPlayer.bukkitPlayer ?: return
            player.apply {
                gameMode = GameMode.SPECTATOR
                sendPackets {
                    forPlayer {
                        gameMode(GameMode.ADVENTURE)
                    }
                }
                isFlying = true
                allowFlight = true
            }
            virtualPlayerInventory.activate(player)
        }

        fun restore() {
            val player = arenaPlayer.bukkitPlayer ?: return
            if (watching != null) {
                stopWatching()
            }
            virtualPlayerInventory.deactivate(player)
        }
    }
}