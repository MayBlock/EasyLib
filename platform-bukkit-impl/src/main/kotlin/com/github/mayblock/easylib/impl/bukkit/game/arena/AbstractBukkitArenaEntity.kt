package com.github.mayblock.easylib.impl.bukkit.game.arena

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import org.bukkit.entity.Entity
import java.util.*

abstract class AbstractBukkitArenaEntity(
    final override val bukkitEntity: Entity
) : BukkitArenaEntity {
    final override val name: String = bukkitEntity.name
    final override val uuid: UUID = bukkitEntity.uniqueId
    final override val entityId: Int = bukkitEntity.entityId
}