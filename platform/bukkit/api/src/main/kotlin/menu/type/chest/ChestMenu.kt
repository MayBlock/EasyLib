package com.github.mayblock.easylib.api.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack

interface ChestMenu : Menu {
    val title: Component
    val type: ChestMenuType

    /**
     * 某槽位的当前物品：返回真实容器中物品的**拷贝**（修改返回值不影响菜单，写入请用 [setItem]）；
     * 空槽（AIR/数量≤0）返回 null。
     * @throws IllegalArgumentException 下标超出菜单容量
     */
    fun getItem(index: Int): ItemStack?

    /**
     * 改写某槽位的物品（写入真实共享容器，所有观看者立即可见）；`null` 等价清空。
     * 这是「全体生效的真实变更」通道；仅影响单个观察者外观的纯视觉修改请用槽位 DSL 的 `onUpdate`。
     * @throws IllegalArgumentException 下标超出菜单容量
     */
    fun setItem(index: Int, item: ItemStack?)
}
