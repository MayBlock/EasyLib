package com.github.mayblock.easylib.impl.bukkit.overlay

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 进程内唯一的覆盖层断线清理监听器（由 [OverlayManager] 注册/注销，单一共享，
 * 与菜单侧 `MenuInteractionListener` 同模式）：把断线玩家从所有活动覆盖层移除，
 * 防止 viewers 集合泄漏，并保证断线时也派发
 * [com.github.mayblock.easylib.api.bukkit.overlay.OverlayHideEvent]。
 *
 * 同时兜底处理「打开其他容器绕过覆盖层」：玩家一旦打开任意容器界面（`InventoryOpenEvent`，
 * 玩家自己背包视图不会触发该事件，不会误伤），就把其从所有活动覆盖层移除，避免透过容器界面
 * 看到/操作真实背包。
 */
internal class OverlayQuitListener(
    private val overlays: () -> Iterable<AbstractPlayerOverlay>,
) : Listener {

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) = overlays().forEach { it.onPlayerQuit(e.player) }

    @EventHandler
    fun onInventoryOpen(e: InventoryOpenEvent) {
        val player = e.player as? Player ?: return
        overlays().forEach { it.hideIfViewing(player) }
    }
}
