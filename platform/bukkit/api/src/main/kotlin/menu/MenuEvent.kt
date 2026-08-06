package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.base.api.event.Event
import org.bukkit.entity.Player

interface MenuEvent : Event {
    val menu: Menu
}

class MenuOpenEvent(
    override val menu: Menu,
    val player: Player
): MenuEvent

class MenuCloseEvent(
    override val menu: Menu,
    val player: Player
): MenuEvent

/**
 * 菜单被销毁时派发（[Menu.destroy]）。派发时 [menu] 的 `isDestroyed` 已为 true，
 * 事件总线尚未拆除；本事件是订阅者做清理的最后时机，其后总线即被关闭。
 * 幂等：重复 destroy 不会重复派发。
 */
class MenuDestroyEvent(
    override val menu: Menu
): MenuEvent