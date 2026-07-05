package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent

/**
 * 全局单一监听器：按 `inventory.holder` 把 Bukkit 库存事件路由回对应 [RealChestMenu]。
 * 由 MenuManager 在 plugin 上注册/注销（Task A6）。
 */
internal class MenuInteractionListener : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onClick(e: InventoryClickEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        menu.handleClick(e)
    }

    @EventHandler
    fun onOpen(e: InventoryOpenEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        (e.player as? Player)?.let { menu.publishOpen(it) }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onDrag(e: org.bukkit.event.inventory.InventoryDragEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        menu.handleDrag(e)
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        (e.player as? Player)?.let { menu.handleClose(it) }
    }
}
