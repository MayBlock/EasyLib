package com.github.mayblock.easylib.api.bukkit.overlay

import com.github.mayblock.easylib.api.event.Event
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
