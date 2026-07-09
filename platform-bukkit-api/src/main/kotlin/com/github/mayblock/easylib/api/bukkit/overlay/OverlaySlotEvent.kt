package com.github.mayblock.easylib.api.bukkit.overlay

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/** 与某槽位相关的覆盖层事件。 */
interface OverlaySlotEvent : OverlayEvent {
    val index: Int
}

/** 玩家在背包窗口内点击某覆盖槽位时派发（带 Bukkit [ClickType]）。 */
class OverlayClickEvent(
    override val overlay: PlayerOverlay,
    override val index: Int,
    val player: Player,
    val type: ClickType,
) : OverlaySlotEvent

/** 玩家手持某覆盖槽位物品挥动/使用时派发（左/右键）。 */
class OverlayInteractEvent(
    override val overlay: PlayerOverlay,
    override val index: Int,
    val player: Player,
    val action: Action,
) : OverlaySlotEvent {
    enum class Action {
        LEFT_CLICK,
        RIGHT_CLICK,
    }
}
