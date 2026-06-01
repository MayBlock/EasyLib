package com.github.mayblock.easylib.impl.bukkit.game.arena.feature

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.feature.Feature
import com.github.mayblock.easylib.api.feature.FeatureKey
import com.github.mayblock.easylib.impl.bukkit.game.arena.bridge.BridgeEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.plugin.Plugin

class GuardFeature<T>(
    private val plugin: Plugin,
    private val isActive: () -> Boolean,
    private val noBreakBlock: Boolean = true,
    private val noDamage: Boolean = true,
    private val noInteract: Boolean = true,
    private val noDropItem: Boolean = true,
    private val noPickupItem: Boolean = true,
    private val noStarving: Boolean = true,
    worldGuardScope: (WorldGuardScope.() -> Unit)? = null,
) : Feature<T>, Listener where T : BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity> {

    companion object Key : FeatureKey<GuardFeature<*>>("GuardFeature")

    private val worldGuard: WorldGuard? = worldGuardScope?.let {
        WorldGuardScope().apply(it).build()
    }

    override fun onInstall(context: T) {
        context.on(name) {
            on<BridgeEvent.BlockDamageEvent> {
                if (!isActive()) return@on
                isCancelled = noBreakBlock
            }
            on<BridgeEvent.EntityDamageEvent> {
                if (!isActive()) return@on
                isCancelled = noDamage
            }
            on<BridgeEvent.PlayerInteractEvent> {
                if (!isActive()) return@on
                isCancelled = noInteract
            }
            on<BridgeEvent.FoodLevelChangeEvent> {
                if (!isActive()) return@on
                isCancelled = noStarving
            }
            on<BridgeEvent.PlayerPickupItemEvent> {
                if (!isActive()) return@on
                isCancelled = noPickupItem
            }
            on<BridgeEvent.PlayerDropItemEvent> {
                if (!isActive()) return@on
                isCancelled = noDropItem
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun onUninstall(context: T) {
        context.unsubscribeGroup(name)
        HandlerList.unregisterAll(this)
    }

    @EventHandler
    private fun onEntityExplode(e: EntityExplodeEvent) {
        if (!isActive()) return
        e.isCancelled = worldGuard?.scope(e.entity.location) == true
    }

    @EventHandler
    private fun onBlockExplode(e: BlockExplodeEvent) {
        if (!isActive()) return
        e.isCancelled = e.blockList().any { worldGuard?.scope(it.location) == true }
    }
}

@DslMarker
annotation class WorldGuardDsl

@WorldGuardDsl
class WorldGuardScope internal constructor() {
    private var scope: (Location.() -> Boolean)? = null
    private var explode: Boolean = false

    fun scope(block: Location.() -> Boolean) {
        scope = block
    }

    fun explode(active: Boolean) {
        explode = active
    }

    internal fun build(): WorldGuard {
        requireNotNull(scope) { "you have to set scope of protection" }
        return WorldGuard(scope!!, explode)
    }
}

internal data class WorldGuard(
    val scope: Location.() -> Boolean,
    val explode: Boolean,
)