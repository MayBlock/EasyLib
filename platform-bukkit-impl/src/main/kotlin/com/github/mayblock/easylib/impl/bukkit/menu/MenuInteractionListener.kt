package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 全局单一监听器：按 `inventory.holder` 把 Bukkit 库存事件路由回对应 [RealChestMenu]。
 * 由 [owner] 这个 [MenuManager] 在 plugin 上注册/注销（Task A6）。
 *
 * 每个 [MenuManager] 实例各自持有并注册一个本监听器；当同一 server 上存在多个
 * MenuManager 时，各自的监听器都会收到全局的 Bukkit 事件，因此路由前必须校验
 * 目标菜单确实属于本监听器所属的 [owner]，否则同一事件会被多个 manager 重复处理。
 */
internal class MenuInteractionListener(private val owner: MenuManager) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onClick(e: InventoryClickEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        if (menu.owner !== owner) return
        menu.handleClick(e)
    }

    @EventHandler
    fun onOpen(e: InventoryOpenEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        if (menu.owner !== owner) return
        (e.player as? Player)?.let { menu.publishOpen(it) }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDrag(e: org.bukkit.event.inventory.InventoryDragEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        if (menu.owner !== owner) return
        menu.handleDrag(e)
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        if (menu.owner !== owner) return
        (e.player as? Player)?.let { menu.handleClose(it) }
    }

    /**
     * 断线兜底：部分服务端实现在玩家 quit 时不一定先触发 InventoryCloseEvent，
     * 导致菜单永远收不到关闭通知（hideViewers/openViewers 悬空、MenuCloseEvent 不派发）。
     * 若服务端已先触发过 InventoryCloseEvent，这里的 handleClose 双调无害：
     * hideViewers -= player 幂等，openViewers 幂等保护避免 MenuCloseEvent 被重复派发。
     */
    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        val menu = e.player.openInventory.topInventory.holder as? RealChestMenu ?: return
        if (menu.owner !== owner) return
        menu.handleClose(e.player)
    }
}
