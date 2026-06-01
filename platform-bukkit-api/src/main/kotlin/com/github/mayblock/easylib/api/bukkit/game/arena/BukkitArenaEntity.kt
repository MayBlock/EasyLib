package com.github.mayblock.easylib.api.bukkit.game.arena

import com.github.mayblock.easylib.api.game.arena.ArenaEntity
import org.bukkit.entity.Entity

interface BukkitArenaEntity : ArenaEntity {
    val bukkitEntity: Entity
    val entityId: Int
}