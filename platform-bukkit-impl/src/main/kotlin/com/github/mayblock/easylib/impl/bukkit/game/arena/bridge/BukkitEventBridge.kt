package com.github.mayblock.easylib.impl.bukkit.game.arena.bridge

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.event.EventBus
import com.github.mayblock.easylib.api.game.arena.event.ArenaEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.plugin.Plugin

class BukkitEventBridge<A, P : BukkitArenaPlayer, E : BukkitArenaEntity> private constructor(
    private val arena: A,
    private val plugin: Plugin
) where A : EventBus<ArenaEvent>, A : BukkitArena<P, E> {

    companion object {

        fun <A : BukkitArena<P, E>, P : BukkitArenaPlayer, E : BukkitArenaEntity> create(
            arena: A,
            plugin: Plugin
        ): BukkitEventBridge<A, P, E> {
            return BukkitEventBridge(arena, plugin)
        }
    }

    var isDestroyed: Boolean = false
        private set
    private val bukkitListener = BukkitListener().also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }

    fun destroy() {
        if (isDestroyed) {
            throw IllegalStateException("already destroyed")
        }
        isDestroyed = true
        HandlerList.unregisterAll(bukkitListener)
    }

    private inner class BukkitListener : Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        fun onFoodLevelChange(e: FoodLevelChangeEvent) {
            val player = arena.getPlayer(e.entity.uniqueId) ?: return
            e.isCancelled = BridgeEvent.FoodLevelChangeEvent(
                player,
                e.foodLevel,
                e.item
            ).also(arena::emit).apply {
                e.foodLevel = foodLevel
            }.isCancelled
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onChangedWorld(e: PlayerChangedWorldEvent) {
            val player = arena.getPlayer(e.player.uniqueId) ?: return
            arena.emit(BridgeEvent.PlayerChangedWorldEvent(player, e.from))
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onDropItem(e: PlayerDropItemEvent) {
            val player = arena.getPlayer(e.player.uniqueId) ?: return
            e.isCancelled = BridgeEvent.PlayerDropItemEvent(player, e.itemDrop)
                .also(arena::emit).isCancelled
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onPickupItem(e: EntityPickupItemEvent) {
            val player = arena.getPlayer(e.entity.uniqueId) ?: return
            e.isCancelled = BridgeEvent.PlayerPickupItemEvent(player, e.item, e.remaining)
                .also(arena::emit).isCancelled
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onDeath(e: PlayerDeathEvent) {
            val player = arena.getPlayer(e.entity.uniqueId) ?: return
            BridgeEvent.PlayerDeathEvent(
                player,
                e.deathMessage,
                e.keepInventory,
                e.keepLevel,
                e.newExp,
                e.newLevel,
                e.newTotalExp,
                e.droppedExp,
                e.damageSource,
                e.drops
            ).also(arena::emit)
                .also {
                    e.deathMessage = it.deathMessage
                    e.keepInventory = it.keepInventory
                    e.keepLevel = it.keepLevel
                    e.newExp = it.newExp
                    e.newLevel = it.newLevel
                    e.newTotalExp = it.newTotalExp
                    e.droppedExp = it.droppedExp
                }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onInteract(e: PlayerInteractEvent) {
            val player = arena.getPlayer(e.player.uniqueId) ?: return
            e.isCancelled = BridgeEvent.PlayerInteractEvent(
                player,
                e.item,
                e.action,
                e.clickedBlock,
                e.blockFace,
                e.isBlockInHand,
                e::useInteractedBlock,
                e::useItemInHand,
                e.hand,
                e.clickedPosition,
                e.material
            ).also(arena::emit).isCancelled
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onBlockDamage(e: BlockDamageEvent) {
            val player = arena.getPlayer(e.player.uniqueId) ?: return
            e.isCancelled = BridgeEvent.BlockDamageEvent(player, e.block, e.instaBreak)
                .also(arena::emit)
                .also {
                    e.instaBreak = it.instaBreak
                }.isCancelled
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onMove(e: PlayerMoveEvent) {
            val player = arena.getPlayer(e.player.uniqueId) ?: return
            e.isCancelled = BridgeEvent.PlayerMoveEvent(player, e.from, e.to)
                .also(arena::emit)
                .also { ae ->
                    e.from = ae.from
                    if (e.to != ae.to) {
                        e.to?.let(e::setTo)
                    }
                }.isCancelled
        }

        @EventHandler
        fun onToggleSneak(e: PlayerToggleSneakEvent) {
            val player = arena.getPlayer(e.player.uniqueId) ?: return
            e.isCancelled = BridgeEvent.PlayerToggleSneakEvent(player, e.isSneaking)
                .also(arena::emit)
                .isCancelled
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onDamage(e: EntityDamageEvent) {
            val player = arena.getPlayer(e.entity.uniqueId) ?: return
            when (e) {
                is EntityDamageByEntityEvent -> onDamageByEntity(player, e)
                is EntityDamageByBlockEvent -> onDamageByBlock(player, e)
                else -> onGenericDamage(player, e)
            }
        }

        private fun onGenericDamage(entity: BukkitArenaPlayer, e: EntityDamageEvent) {
            e.isCancelled = BridgeEvent.EntityDamageEvent(entity, e.damage, e.finalDamage, e.damageSource, e.cause)
                .also(arena::emit)
                .also {
                    e.damage = it.damage
                }.isCancelled
        }

        private fun onDamageByEntity(player: BukkitArenaPlayer, e: EntityDamageByEntityEvent) {
            e.isCancelled = BridgeEvent.EntityDamageByEntityEvent(
                player,
                e.damager,
                e.damage,
                e.finalDamage,
                e.damageSource,
                e.cause
            ).also(arena::emit).also {
                e.damage = it.damage
            }.isCancelled
        }

        private fun onDamageByBlock(player: BukkitArenaPlayer, e: EntityDamageByBlockEvent) {
            e.isCancelled = BridgeEvent.EntityDamageByBlockEvent(
                player,
                e.damager,
                e.damagerBlockState,
                e.damage,
                e.finalDamage,
                e.damageSource,
                e.cause
            ).also(arena::emit).also {
                e.damage = it.damage
            }.isCancelled
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onEntitySpawn(e: EntitySpawnEvent) {
            if (e.entity is Player) return
            val entity = arena.createArenaEntity(e.entity)
            if (!e.isCancelled) {
                arena.spawnEntity(entity)
            }
        }

        fun onGenericEntitySpawn(entity: BukkitArenaEntity, e: EntitySpawnEvent) {
            e.isCancelled = BridgeEvent.EntitySpawnEvent(
                entity,
                e.location
            ).also(arena::emit).isCancelled
        }

        fun onCreatureSpawn(entity: BukkitArenaEntity, e: CreatureSpawnEvent) {
            e.isCancelled = BridgeEvent.CreatureSpawnEvent(
                entity,
                e.location,
                e.spawnReason
            ).also(arena::emit).isCancelled
        }

        fun onSpawnerSpawn(entity: BukkitArenaEntity, e: SpawnerSpawnEvent) {
            e.isCancelled = BridgeEvent.SpawnerSpawnEvent(
                entity,
                e.location,
                e.spawner
            ).also(arena::emit).isCancelled
        }
    }
}