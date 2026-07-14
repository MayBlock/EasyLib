package com.github.mayblock.easylib.impl.bukkit.prompt

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 断线清理监听器（与覆盖层侧的 `OverlayQuitListener`、菜单侧的 `MenuInteractionListener` 同模式）：
 * 玩家断线时把其 pending 的 prompt（若有）以 `null` 结算并从 [PromptApiImpl] 内部摘除，
 * 防止玩家中途下线导致 prompt 的回调与位置信息永久滞留在 map 里。
 *
 * 由 [com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib] 注册/在 `close()` 时注销。
 */
internal class PromptQuitListener : Listener {

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) = PromptApiImpl.onPlayerQuit(e.player.uniqueId)
}
