package com.github.mayblock.easylib.platform.bukkit.api.overlay

import com.github.mayblock.easylib.base.api.event.Event
import org.bukkit.entity.Player

/** 覆盖层事件根类型（独立于菜单事件层 `MenuEvent`）。 */
interface OverlayEvent : Event {
    val overlay: PlayerOverlay
}

/** 覆盖层对某玩家开启时派发。 */
class OverlayShowEvent(
    override val overlay: PlayerOverlay,
    val player: Player,
) : OverlayEvent

/** 覆盖层对某玩家关闭（hide / 销毁时逐个移除 / 断线）时派发。 */
class OverlayHideEvent(
    override val overlay: PlayerOverlay,
    val player: Player,
) : OverlayEvent

/**
 * 覆盖层被销毁时派发（[PlayerOverlay.destroy]）。派发时 [overlay] 的 `isDestroyed` 已为 true，
 * 事件总线尚未拆除；本事件是订阅者做清理的最后时机，其后总线即被关闭。
 * 幂等：重复 destroy 不会重复派发。
 */
class OverlayDestroyEvent(
    override val overlay: PlayerOverlay,
) : OverlayEvent
