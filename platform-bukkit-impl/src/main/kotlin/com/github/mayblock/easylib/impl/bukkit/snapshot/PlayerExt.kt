package com.github.mayblock.easylib.impl.bukkit.snapshot

import com.github.mayblock.easylib.api.bukkit.snapshot.ItemStackSnapshot
import com.github.mayblock.easylib.api.bukkit.snapshot.PlayerRestoreFlag
import com.github.mayblock.easylib.api.bukkit.snapshot.PlayerSnapshot
import com.github.mayblock.easylib.api.bukkit.snapshot.PlayerStatsSnapshot
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

fun Player.createPlayerSnapshot() = PlayerSnapshot(
    PlayerStatsSnapshot(this),
    this.inventory.createSnapshot(),
    ItemStackSnapshot(this.enderChest.contents.toList()),
    this.location.clone()
)

fun Inventory.createSnapshot() = ItemStackSnapshot(this.contents.toList())
fun Inventory.restoreSnapshot(snapshot: ItemStackSnapshot) {
    contents = snapshot.contents.toTypedArray()
}

fun Player.restorePlayerSnapshot(
    snapshot: PlayerSnapshot,
    flags: Set<PlayerRestoreFlag> = PlayerRestoreFlag.entries.toSet(),
) {
    if (PlayerRestoreFlag.STATS in flags) {
        gameMode = snapshot.stats.gameMode
        val maxHealth = getAttribute(Attribute.MAX_HEALTH)!!.value
        health = snapshot.stats.health.coerceAtMost(maxHealth)
        foodLevel = snapshot.stats.foodLevel
        saturation = snapshot.stats.saturation
        exhaustion = snapshot.stats.exhaustion
        exp = snapshot.stats.exp
        level = snapshot.stats.level
        isFlying = snapshot.stats.isFlying
        allowFlight = snapshot.stats.allowFlight
        isSneaking = snapshot.stats.isSneaking
        activePotionEffects.forEach {
            removePotionEffect(it.type)
        }
        snapshot.stats.activeEffects.forEach(::addPotionEffect)
    }
    if (PlayerRestoreFlag.INVENTORY in flags) {
        inventory.restoreSnapshot(snapshot.inventory)
        enderChest.restoreSnapshot(snapshot.enderChest)
    }
    if (PlayerRestoreFlag.LOCATION in flags) {
        teleport(snapshot.location)
    }
}