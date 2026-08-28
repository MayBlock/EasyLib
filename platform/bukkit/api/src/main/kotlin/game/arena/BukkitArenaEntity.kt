package com.github.mayblock.easylib.platform.bukkit.api.game.arena

import com.github.mayblock.easylib.base.api.game.arena.ArenaEntity
import org.bukkit.entity.Entity

interface BukkitArenaEntity : ArenaEntity {
    val bukkitEntity: Entity
    val entityId: Int
}