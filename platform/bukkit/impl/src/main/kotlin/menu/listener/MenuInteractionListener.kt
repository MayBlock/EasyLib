package com.github.mayblock.easylib.base.impl.bukkit.menu.listener

import com.github.mayblock.easylib.base.impl.bukkit.menu.BukkitMenu
import com.github.mayblock.easylib.base.impl.bukkit.menu.MenuManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.InventoryHolder

/**
 * 全局单一监听器：按接口路由（[com.github.mayblock.easylib.base.impl.bukkit.menu.BukkitMenu]）把 Bukkit 库存事件转发回对应菜单实例，
 * 对具体 UI 类型（chest/铁砧/……）零感知——新增 UI 类型只需实现 [com.github.mayblock.easylib.base.impl.bukkit.menu.BukkitMenu]，无需改动本监听器。
 * 由 [owner] 这个 [com.github.mayblock.easylib.base.impl.bukkit.menu.MenuManager] 在 plugin 上注册/注销。
 *
 * 每个 [com.github.mayblock.easylib.base.impl.bukkit.menu.MenuManager] 实例各自持有并注册一个本监听器；当同一 server 上存在多个
 * MenuManager 时，各自的监听器都会收到全局的 Bukkit 事件，因此路由前必须裁定归属，
 * 否则同一事件会被多个 manager 重复处理。归属由 [owner] 查自己的名册回答，
 * 菜单对象自身不携带归属信息。
 */
internal class MenuInteractionListener(private val owner: MenuManager) : Listener {

    /** holder → 菜单：归属裁定交给 [MenuManager] 自查名册（见 [MenuManager.route]）。 */
    fun route(holder: InventoryHolder?): BukkitMenu? = owner.route(holder)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInvClick(e: InventoryClickEvent) {
        route(e.inventory.holder)?.handleClick(e)
    }

    @EventHandler
    fun onInvOpen(e: InventoryOpenEvent) {
        val player = e.player as? Player ?: return
        route(e.inventory.holder)?.handleOpen(player)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInvDrag(e: InventoryDragEvent) {
        route(e.inventory.holder)?.handleDrag(e)
    }

    @EventHandler
    fun onInvClose(e: InventoryCloseEvent) {
        val player = e.player as? Player ?: return
        route(e.inventory.holder)?.handleClose(player)
    }

    /**
     * 断线兜底：部分服务端实现在玩家 quit 时不一定先触发 InventoryCloseEvent，
     * 导致菜单永远收不到关闭通知（viewers 悬空、MenuCloseEvent 不派发）。
     * 若服务端已先触发过 InventoryCloseEvent，这里的 handleClose 双调无害：
     * viewers.remove 幂等保护避免 MenuCloseEvent 被重复派发。
     */
    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        route(e.player.openInventory.topInventory.holder)?.handleClose(e.player)
    }
}