package com.github.mayblock.easylib.api.bukkit.snapshot

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect

data class PlayerSnapshot(
    val stats: PlayerStatsSnapshot,
    val inventory: ItemStackSnapshot,
    val enderChest: ItemStackSnapshot,
    val location: Location
)

data class ItemStackSnapshot(
    val contents: List<ItemStack?>,
)

data class PlayerStatsSnapshot(
    val gameMode: GameMode,
    val health: Double,
    val foodLevel: Int,
    val saturation: Float,
    val exhaustion: Float,
    val exp: Float,
    val level: Int,
    val isFlying: Boolean,
    val allowFlight: Boolean,
    val isSneaking: Boolean,

    val activeEffects: List<PotionEffect>,
) {
    constructor(player: Player) : this(
        player.gameMode,
        player.health,
        player.foodLevel,
        player.saturation,
        player.exhaustion,
        player.exp,
        player.level,
        player.isFlying,
        player.allowFlight,
        player.isSneaking,
        player.activePotionEffects.toList()
    )
}