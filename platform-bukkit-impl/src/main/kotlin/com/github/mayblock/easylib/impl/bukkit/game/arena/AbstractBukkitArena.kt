package com.github.mayblock.easylib.impl.bukkit.game.arena

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.impl.bukkit.game.arena.bridge.BukkitEventBridge
import com.github.mayblock.easylib.impl.game.arena.AbstractEventfulArena
import org.bukkit.plugin.Plugin

abstract class AbstractBukkitArena<Player : BukkitArenaPlayer, Entity : BukkitArenaEntity>(
    name: String,
    @Suppress("unused")
    protected val plugin: Plugin
) : AbstractEventfulArena<Player, Entity>(name), BukkitArena<Player, Entity> {

    private val bridge = BukkitEventBridge.create(this, plugin)

    override fun onPostDisableArena() {
        bridge.destroy()
        super.onPostDisableArena()
    }
}