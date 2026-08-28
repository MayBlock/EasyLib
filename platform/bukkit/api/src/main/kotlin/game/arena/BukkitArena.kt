package com.github.mayblock.easylib.platform.bukkit.api.game.arena

import com.github.mayblock.easylib.base.api.event.EventBus
import com.github.mayblock.easylib.base.api.game.arena.Arena
import com.github.mayblock.easylib.base.api.game.arena.event.ArenaEvent

interface BukkitArena<Player : BukkitArenaPlayer, Entity : BukkitArenaEntity>
    : Arena<Player, Entity>, EventBus<ArenaEvent> {

    /**
     * 将一个原生 Bukkit 实体包装为本 arena 的 [Entity]。
     *
     * @return 若本 arena 不关心/不认领该实体（例如它不属于本 arena 的世界或范围），返回 `null`；
     * 调用方（如 [com.github.mayblock.easylib.platform.bukkit.impl.game.arena.bridge.BukkitEventBridge]）
     * 应将 `null` 视为“忽略此次生成事件”，不得据此抛出异常或强行生成实体。
     */
    fun createArenaEntity(entity: org.bukkit.entity.Entity): Entity?
}