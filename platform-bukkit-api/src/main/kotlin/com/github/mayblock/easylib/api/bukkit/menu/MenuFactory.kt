package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.PageableChestMenuScope

interface MenuFactory {

    /**
     * 创建箱子菜单。
     * @param hidePlayerInventory 打开菜单时是否用数据包屏蔽玩家背包物品（关闭菜单后自动恢复）。
     *   默认 true；声明了 `placeable` 槽位的菜单必须显式传 false，否则构建期报错。
     */
    fun createChestMenu(
        type: ChestMenuType,
        hidePlayerInventory: Boolean = true,
        builder: PageableChestMenuScope.() -> Unit,
    ): ChestMenu
}