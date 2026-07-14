package com.github.mayblock.easylib.impl.bukkit.game.arena.service

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope
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
    playerInventory: (PlayerOverlayScope.() -> Unit)? = null,
) : Service {

    val playerOverlay = api.overlayFactory.create(playerInventory ?: {})

    companion object Key : ServiceKey<SpectatorService<*>>("SpectatorService")

    private val spectators = mutableListOf<Spectator>()

    override fun onRegister() {}

    // 服务注销时若不清理，spectators 里持有的玩家引用与 playerOverlay 的包监听/更新循环都会泄漏
    // （泄漏链：Service 卸载 -> Spectator 未 restore -> overlay 未 destroy -> viewers/packetListener 常驻）。
    override fun onUnregister() {
        spectators.toList().forEach(::removeSpectator)
        playerOverlay.destroy()
    }

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

        private var previousGameMode: GameMode? = null
        private var previousAllowFlight = false
        private var previousFlying = false

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
            previousGameMode = player.gameMode
            previousAllowFlight = player.allowFlight
            previousFlying = player.isFlying
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
            playerOverlay.show(player)
        }

        /**
         * 还原观战前的状态（gamemode/allowFlight/isFlying）并停止跟拍、隐藏 overlay。
         *
         * 已知限制：若玩家在观战期间离线，[arenaPlayer.bukkitPlayer] 为 null，
         * 本方法无法对其执行任何还原操作——SPECTATOR 模式会持久化到玩家重新登录，
         * 且不会自动补偿性还原（未做 join 时的补偿机制，超出本次修复范围）。
         */
        fun restore() {
            if (watching != null) {
                stopWatching()
            }
            val player = arenaPlayer.bukkitPlayer ?: return
            previousGameMode?.let { player.gameMode = it } // 真实 gamemode 变更会重发权威包，纠正假 ADVENTURE
            player.allowFlight = previousAllowFlight
            player.isFlying = previousFlying
            playerOverlay.hide(player)
        }
    }
}