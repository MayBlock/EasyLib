package com.github.mayblock.easylib.platform.bukkit.impl.menu

import com.github.mayblock.easylib.platform.bukkit.api.menu.Menu
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * impl 侧所有 Bukkit 菜单的公共契约：既是路由锚点（[InventoryHolder]——Bukkit 事件经
 * holder 找回菜单），也是 [com.github.mayblock.easylib.platform.bukkit.impl.menu.listener.MenuInteractionListener] 的交互分发目标。
 * 新增 UI 类型（如铁砧）：实现本接口即可自动获得事件路由，无需改动监听器。
 */
internal interface BukkitMenu : Menu, InventoryHolder {

    fun handleOpen(player: Player)
    fun handleClick(e: InventoryClickEvent)
    fun handleDrag(e: InventoryDragEvent)
    fun handleClose(player: Player)

    /** 与 [com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenu.getItem] 同契约；impl 内更新循环等对所有菜单类型统一依赖它。 */
    fun getItem(index: Int): ItemStack?

    /** 与 [com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenu.setItem] 同契约。 */
    fun setItem(index: Int, item: ItemStack?)
}
