package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.base.api.event.EventSource
import com.github.mayblock.easylib.base.api.util.Destroyable
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 菜单对外只暴露「订阅侧」事件源（[EventSource]）——可监听 [MenuEvent]（开/关、槽点击等），
 * 但 `emit` 被实现内部持有，外部无法伪造事件。
 *
 * 本接口不支持库外部实现，实例只应经 [MenuFactory] 创建。
 */
interface Menu : Destroyable, EventSource<MenuEvent> {
    fun open(player: Player)
}
