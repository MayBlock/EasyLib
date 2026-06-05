package com.github.mayblock.easylib.api.bukkit.game.arena

import com.github.mayblock.easylib.api.game.arena.ArenaPlayer
import org.bukkit.Location
import org.bukkit.entity.Player

interface BukkitArenaPlayer : ArenaPlayer {
    /**
     * @return 当玩家从服务器离线时，返回null
     */
    val bukkitPlayer: Player?
    val location: Location
    val isOnline: Boolean
    override fun sendMessage(message: String) {
        bukkitPlayer?.sendMessage(message)
    }
}
