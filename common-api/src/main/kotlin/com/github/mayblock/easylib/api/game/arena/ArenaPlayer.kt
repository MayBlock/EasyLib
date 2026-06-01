package com.github.mayblock.easylib.api.game.arena

import java.util.*

interface ArenaPlayer {
    val arena: Arena<out ArenaPlayer, out ArenaEntity>
    val name: String
    val uuid: UUID
    var displayName: String

    fun sendMessage(message: String)
}