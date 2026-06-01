package com.github.mayblock.easylib.api.game.arena

import com.github.mayblock.easylib.api.feature.FeatureRegistry
import com.github.mayblock.easylib.api.serialze.Serializer
import com.github.mayblock.easylib.api.service.ServiceRegistry
import java.util.*

interface Arena<Player : ArenaPlayer, Entity : ArenaEntity> {

    val name: String
    val players: List<Player>
    val entities: List<Entity>
    var isArenaEnabled: Boolean

    val features: FeatureRegistry<Arena<Player, Entity>>
    val services: ServiceRegistry

    fun addPlayer(player: Player)
    fun removePlayer(uuid: UUID): Boolean
    fun <T : Player> removePlayer(player: T): Boolean
    fun getPlayer(uuid: UUID): Player?
    fun <T : Player> getPlayer(uuid: UUID, type: Class<T>): T?

    @Throws(EntitySpawnException::class)
    fun spawnEntity(entity: Entity)
    fun getEntity(uuid: UUID): Entity?
    fun destroyEntity(entity: Entity): Boolean
    fun broadcast(message: String, selector: ArenaPlayer.() -> Boolean = { true })

    interface Configurable<Config : Serializer<Serialized, Config>, Serialized> {
        val config: Config
    }
}

