package com.github.mayblock.easylib.api.game.arena

open class ArenaException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
class FailedJoinException(message: String?, cause: Throwable? = null) : ArenaException(message, cause)
class EntitySpawnException(message: String?, cause: Throwable? = null) : ArenaException(message, cause)