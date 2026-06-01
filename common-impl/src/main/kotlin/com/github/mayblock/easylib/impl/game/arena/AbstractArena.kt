package com.github.mayblock.easylib.impl.game.arena

import com.github.mayblock.easylib.api.event.EventBus
import com.github.mayblock.easylib.api.game.arena.*
import com.github.mayblock.easylib.api.game.arena.event.*
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import com.github.mayblock.easylib.impl.feature.SimpleFeatureRegistry
import com.github.mayblock.easylib.impl.service.SimpleServiceRegistry
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import java.util.*

abstract class AbstractArena<Player : ArenaPlayer, Entity : ArenaEntity>(
    final override val name: String,
) : Arena<Player, Entity> {

    private val _players = mutableListOf<Player>()
    private val _entities = mutableListOf<Entity>()

    override val players: List<Player> get() = _players
    override val entities: List<Entity> get() = _entities

    override val features = SimpleFeatureRegistry<Arena<Player, Entity>>(this)
    override val services = SimpleServiceRegistry()

    final override var isArenaEnabled: Boolean = false
        set(value) {
            if (field == value) return
            if (value) onEnable() else onDisable()
            field = value
        }

    override fun addPlayer(player: Player) {
        require(isArenaEnabled) { "Arena must be enabled before adding players" }
        require(!players.contains(player)) { "Player $name already exists" }
        _players.add(player)
    }

    override fun <T : Player> removePlayer(player: T) = _players.remove(player)
    override fun spawnEntity(entity: Entity) {
        require(isArenaEnabled) { "Arena must be enabled before spawning entities" }
        _entities.add(entity)
    }

    override fun destroyEntity(entity: Entity) = _entities.remove(entity)

    override fun getPlayer(uuid: UUID): Player? = players.firstOrNull { it.uuid == uuid }
    override fun <T : Player> getPlayer(uuid: UUID, type: Class<T>): T? =
        players.filterIsInstance(type).firstOrNull { it.uuid == uuid }

    override fun getEntity(uuid: UUID): Entity? =
        entities.firstOrNull { it.uuid == uuid }

    override fun removePlayer(uuid: UUID): Boolean =
        players.firstOrNull { it.uuid == uuid }?.let(::removePlayer) ?: false

    override fun broadcast(message: String, selector: ArenaPlayer.() -> Boolean) {
        players.filter { it.selector() }.forEach {
            it.sendMessage(message)
        }
    }

    private fun onEnable() {
        try {
            onEnableArena()
        } catch (e: Exception) {
            e.printStackTrace()
            isArenaEnabled = false
        }
    }

    private fun onDisable() {
        onDisableArena()
        players.toList().forEach(::removePlayer)
        entities.toList().forEach(::destroyEntity)
        features.uninstallAll()
        services.unregisterAll()
        onPostDisableArena()
    }

    protected abstract fun onEnableArena()
    protected abstract fun onDisableArena()
    protected open fun onPostDisableArena() {}
}

abstract class AbstractEventfulArena<Player : ArenaPlayer, Entity : ArenaEntity>(
    name: String,
    private val eventBus: SimpleEventBus<ArenaEvent> = SimpleEventBus()
) : AbstractArena<Player, Entity>(name), EventBus<ArenaEvent> by eventBus {

    @Throws(FailedJoinException::class)
    override fun addPlayer(player: Player) {
        ArenaJoinAttemptEvent(player).also(::emit).takeIf { !it.isCancelled }?.let {
            super.addPlayer(player)
            emit(ArenaJoinedEvent(player))
        } ?: throw FailedJoinException("Join attempt for ${player.name} was cancelled (${player::class.java.name})")
    }

    override fun <T : Player> removePlayer(player: T) = super.removePlayer(player).ifTrue {
        emit(ArenaLeaveEvent(player))
    }

    override fun spawnEntity(entity: Entity) {
        ArenaEntityEvent.SpawnEvent(entity).also(::emit).takeIf { !it.isCancelled }?.let {
            super.spawnEntity(entity)
        } ?: throw EntitySpawnException("Entity ${entity.name} generate blocked")
    }

    override fun destroyEntity(entity: Entity): Boolean = super.destroyEntity(entity).ifTrue {
        emit(ArenaEntityEvent.DestroyEvent(entity))
    }

    override fun onPostDisableArena() {
        eventBus.unsubscribeAll() // 取消订阅所有事件
    }
}
