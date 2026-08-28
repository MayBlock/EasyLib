package com.github.mayblock.easylib.platform.bukkit.impl.game.arena

import com.github.mayblock.easylib.base.impl.game.arena.AbstractArenaPlayer
import com.github.mayblock.easylib.platform.bukkit.api.game.arena.BukkitArena
import com.github.mayblock.easylib.platform.bukkit.api.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.platform.bukkit.api.game.arena.BukkitArenaPlayer
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
    override val isOnline: Boolean get() = bukkitPlayer?.isOnline == true
    override val bukkitPlayer: Player? get() = Bukkit.getPlayer(uuid)
}

