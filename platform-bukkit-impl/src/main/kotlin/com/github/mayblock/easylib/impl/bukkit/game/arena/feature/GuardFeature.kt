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
    private val noTargetedByEntity: Boolean = true,
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
            on<BridgeEvent.PlayerTargetedByEntityEvent> {
                if (!isActive()) return@on
                if (noTargetedByEntity) isCancelled = true
            }
            on<BridgeEvent.BlockDamageEvent> {
                if (!isActive()) return@on
                if (noBreakBlock) isCancelled = true
            }
            on<BridgeEvent.EntityDamageEvent> {
                if (!isActive()) return@on
                if (noDamage) isCancelled = true
            }
            on<BridgeEvent.PlayerInteractEvent> {
                if (!isActive()) return@on
                if (noInteract) isCancelled = true
            }
            on<BridgeEvent.FoodLevelChangeEvent> {
                if (!isActive()) return@on
                if (noStarving) isCancelled = true
            }
            on<BridgeEvent.PlayerPickupItemEvent> {
                if (!isActive()) return@on
                if (noPickupItem) isCancelled = true
            }
            on<BridgeEvent.PlayerDropItemEvent> {
                if (!isActive()) return@on
                if (noDropItem) isCancelled = true
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun onUninstall(context: T) {
        context.unsubscribeGroup(name)
        HandlerList.unregisterAll(this)
    }

    // 两者都只按「事件发生位置是否落在防护范围内」判断，不逐一过滤 blockList/受影响实体列表——
    // 保持与 WorldGuard 范围判断口径一致、实现简单；如需精确到单个方块可在后续任务扩展。
    @EventHandler
    private fun onEntityExplode(e: EntityExplodeEvent) {
        if (!isActive()) return
        val guard = worldGuard ?: return
        if (guard.explode) return // explode(true) 表示该 arena 显式放行爆炸，不做防护
        if (guard.scope(e.entity.location)) {
            e.isCancelled = true
        }
    }

    @EventHandler
    private fun onBlockExplode(e: BlockExplodeEvent) {
        if (!isActive()) return
        val guard = worldGuard ?: return
        if (guard.explode) return
        if (guard.scope(e.block.location)) {
            e.isCancelled = true
        }
    }
}

@DslMarker
private annotation class WorldGuardDsl

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