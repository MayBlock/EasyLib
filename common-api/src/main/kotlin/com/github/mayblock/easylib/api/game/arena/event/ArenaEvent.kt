package com.github.mayblock.easylib.api.game.arena.event

import com.github.mayblock.easylib.api.event.Event
import com.github.mayblock.easylib.api.game.arena.ArenaEntity
import com.github.mayblock.easylib.api.game.arena.ArenaPlayer

interface ArenaEvent : Event

class ArenaJoinAttemptEvent(
    val player: ArenaPlayer,
    override var isCancelled: Boolean = false
) : ArenaEvent, Event.Cancellable

class ArenaJoinedEvent(
    val player: ArenaPlayer
) : ArenaEvent

class ArenaLeaveEvent(
    val player: ArenaPlayer
) : ArenaEvent

sealed class ArenaEntityEvent(
    val entity: ArenaEntity
) : ArenaEvent {

    class SpawnEvent(
        entity: ArenaEntity,
        override var isCancelled: Boolean = false
    ) : ArenaEntityEvent(entity), Event.Cancellable

    class DestroyEvent(
        entity: ArenaEntity
    ) : ArenaEntityEvent(entity)
}

