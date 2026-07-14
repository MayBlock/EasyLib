package com.github.mayblock.easylib.impl.bukkit.game.arena

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.impl.bukkit.game.arena.bridge.BukkitEventBridge
import com.github.mayblock.easylib.impl.game.arena.AbstractEventfulArena
import org.bukkit.plugin.Plugin

abstract class AbstractBukkitArena<Player : BukkitArenaPlayer, Entity : BukkitArenaEntity>(
    name: String,
    protected val plugin: Plugin
) : AbstractEventfulArena<Player, Entity>(name), BukkitArena<Player, Entity> {

    private var bridge: BukkitEventBridge<*, Player, Entity>? = null

    // AbstractArena.onEnableArena() 是无实现的 abstract hook，这里是它在 Bukkit 平台上的首个具体实现：
    // 每次 arena enable 都重新创建 bridge，与 onPostDisableArena 中的销毁对称。
    // 若子类需要覆盖以添加自身的 enable 逻辑，必须调用 super.onEnableArena() 以保留 bridge 的创建。
    override fun onEnableArena() {
        bridge = BukkitEventBridge.create(this, plugin)
    }

    override fun onPostDisableArena() {
        bridge?.destroy()
        bridge = null
        super.onPostDisableArena()
    }
}