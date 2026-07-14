package com.github.mayblock.easylib.api.bukkit.game.arena

import com.github.mayblock.easylib.api.game.arena.ArenaPlayer
import org.bukkit.Location
import org.bukkit.entity.Player

interface BukkitArenaPlayer : ArenaPlayer {
    /**
     * @return 当玩家从服务器离线时，返回null
     */
    val bukkitPlayer: Player?

    /**
     * 玩家当前位置；在线时取自 [bukkitPlayer]，离线时回退到 [org.bukkit.OfflinePlayer.getLocation]（bed/respawn 位置）。
     *
     * @return 当玩家从未上过线、或服务端没有为其记录任何位置时，返回 `null`（BREAKING：曾经是非空类型）。
     */
    val location: Location?
    val isOnline: Boolean
    override fun sendMessage(message: String) {
        bukkitPlayer?.sendMessage(message)
    }
}
