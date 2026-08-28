package com.github.mayblock.easylib.platform.bukkit.api.menu

import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.dsl.PageableChestMenuScope

interface MenuFactory {

    /**
     * 创建箱子菜单。
     * @param hidePlayerInventory 打开菜单时是否用数据包屏蔽玩家背包物品（关闭菜单后自动恢复）。
     *   默认 true；声明了 `onPlace` 放行处理器的槽位所在菜单必须显式传 false，否则构建期报错。
     */
    fun createChestMenu(
        type: ChestMenuType,
        hidePlayerInventory: Boolean = true,
        block: PageableChestMenuScope.() -> Unit,
    ): ChestMenu
}