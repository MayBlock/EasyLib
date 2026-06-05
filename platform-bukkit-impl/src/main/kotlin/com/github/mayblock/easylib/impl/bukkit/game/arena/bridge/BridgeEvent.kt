package com.github.mayblock.easylib.impl.bukkit.game.arena.bridge

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.event.Event
import com.github.mayblock.easylib.api.game.arena.event.ArenaEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.CreatureSpawner
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

object BridgeEvent {

    class PlayerTargetedByEntityEvent(
        var target: BukkitArenaPlayer,
        val entity: Entity,
        val reason: EntityTargetEvent.TargetReason,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    class FoodLevelChangeEvent(
        val player: BukkitArenaPlayer,
        var foodLevel: Int,
        val item: ItemStack?,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    class PlayerChangedWorldEvent(
        val player: BukkitArenaPlayer,
        val from: World
    ) : ArenaEvent

    class PlayerPickupItemEvent(
        val player: BukkitArenaPlayer,
        val item: Item,
        val remaining: Int,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    class PlayerDropItemEvent(
        val player: BukkitArenaPlayer,
        val item: Item,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    class PlayerDeathEvent(
        val player: BukkitArenaPlayer,
        var deathMessage: String?,
        var keepInventory: Boolean,
        var keepLevel: Boolean,
        var newExp: Int,
        var newLevel: Int,
        var newTotalExp: Int,
        var droppedExp: Int,
        val damageSource: DamageSource,
        val drops: MutableList<ItemStack>
    ) : ArenaEvent

    class PlayerInteractEvent(
        val player: BukkitArenaPlayer,
        val item: ItemStack?,
        val action: Action,
        val clickedBlock: Block?,
        val blockFace: BlockFace,
        val isBlockInHand: Boolean,
        val useInteractedBlock: () -> org.bukkit.event.Event.Result,
        val useItemInHand: () -> org.bukkit.event.Event.Result,
        val hand: EquipmentSlot?,
        val clickedPosition: Vector?,
        val material: Material,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    class BlockDamageEvent(
        val player: BukkitArenaPlayer,
        val block: Block,
        var instaBreak: Boolean,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    class PlayerMoveEvent(
        val player: BukkitArenaPlayer,
        var from: Location,
        var to: Location?,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    class PlayerToggleSneakEvent(
        val player: BukkitArenaPlayer,
        val isSneaking: Boolean,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    open class EntityDamageEvent(
        val player: BukkitArenaPlayer,
        var damage: Double,
        val finalDamage: Double,
        val damageSource: DamageSource,
        val cause: org.bukkit.event.entity.EntityDamageEvent.DamageCause,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    class EntityDamageByEntityEvent(
        player: BukkitArenaPlayer,
        val damager: Entity,
        damage: Double,
        finalDamage: Double,
        damageSource: DamageSource,
        cause: org.bukkit.event.entity.EntityDamageEvent.DamageCause,
        override var isCancelled: Boolean = false
    ) : EntityDamageEvent(player, damage, finalDamage, damageSource, cause, isCancelled)

    class EntityDamageByBlockEvent(
        player: BukkitArenaPlayer,
        val damager: Block?,
        val damagerState: BlockState?,
        damage: Double,
        finalDamage: Double,
        damageSource: DamageSource,
        cause: org.bukkit.event.entity.EntityDamageEvent.DamageCause,
        override var isCancelled: Boolean = false
    ) : EntityDamageEvent(player, damage, finalDamage, damageSource, cause, isCancelled)

    open class EntitySpawnEvent(
        val entity: BukkitArenaEntity,
        val location: Location,
        override var isCancelled: Boolean = false
    ) : ArenaEvent, Event.Cancellable

    open class CreatureSpawnEvent(
        entity: BukkitArenaEntity,
        location: Location,
        val spawnReason: org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason,
        override var isCancelled: Boolean = false
    ) : EntitySpawnEvent(entity, location)

    open class SpawnerSpawnEvent(
        entity: BukkitArenaEntity,
        location: Location,
        val spawner: CreatureSpawner,
        override var isCancelled: Boolean = false
    ) : EntitySpawnEvent(entity, location)
}