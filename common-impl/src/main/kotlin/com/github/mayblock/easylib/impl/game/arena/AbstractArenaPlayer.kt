package com.github.mayblock.easylib.impl.game.arena

import com.github.mayblock.easylib.api.event.EventBus
import com.github.mayblock.easylib.api.game.arena.Arena
import com.github.mayblock.easylib.api.game.arena.ArenaEntity
import com.github.mayblock.easylib.api.game.arena.ArenaPlayer
import com.github.mayblock.easylib.api.game.arena.event.ArenaEvent
import com.github.mayblock.easylib.impl.event.SimpleEventBus

abstract class AbstractArenaPlayer(
    override val arena: Arena<out ArenaPlayer, out ArenaEntity>
) : ArenaPlayer {
    final override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as AbstractArenaPlayer
        return uuid == other.uuid
    }

    final override fun hashCode(): Int {
        return uuid.hashCode()
    }
}

abstract class AbstractEventfulArenaPlayer(
    arena: Arena<out ArenaPlayer, out ArenaEntity>,
    eventBus: EventBus<ArenaEvent> = SimpleEventBus()
) : AbstractArenaPlayer(arena), EventBus<ArenaEvent> by eventBus