package com.github.mayblock.easylib.api.bukkit.game.arena

import com.github.mayblock.easylib.api.event.EventBus
import com.github.mayblock.easylib.api.game.arena.Arena
import com.github.mayblock.easylib.api.game.arena.event.ArenaEvent

interface BukkitArena<Player : BukkitArenaPlayer, Entity : BukkitArenaEntity>
    : Arena<Player, Entity>, EventBus<ArenaEvent> {

    fun createArenaEntity(entity: org.bukkit.entity.Entity): Entity
}