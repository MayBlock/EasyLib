package com.github.mayblock.easylib.impl.bukkit.game.arena

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.impl.game.arena.AbstractArenaPlayer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.*

abstract class AbstractBukkitArenaPlayer(
    bukkitPlayer: Player,
    arena: BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>
) : AbstractArenaPlayer(arena), BukkitArenaPlayer {

    final override val uuid: UUID = bukkitPlayer.uniqueId
    final override val name: String = bukkitPlayer.name
    override var displayName: String = bukkitPlayer.displayName
    override val location: Location get() = bukkitPlayer?.location ?: Bukkit.getOfflinePlayer(uuid).location!!
    override val isOnline: Boolean get() = bukkitPlayer != null && bukkitPlayer!!.isOnline
    override val bukkitPlayer: Player? get() = Bukkit.getPlayer(uuid)
}

