package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.util.Destroyable
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

    /**
     * 某槽位的当前物品（直接读真实容器）；槽位为空（AIR/数量≤0）返回 null。
     * @throws IllegalArgumentException 槽位索引越界（超出菜单容量）
     */
    fun getItem(index: Int): ItemStack?

    /**
     * 改写某槽位的物品（直接写真实共享容器，所有观看者立即可见）；`null` 等价于清空（AIR）。
     * @throws IllegalArgumentException 槽位索引越界（超出菜单容量）
     */
    fun setItem(index: Int, item: ItemStack?)
}
