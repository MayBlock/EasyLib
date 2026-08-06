package com.github.mayblock.easylib.api.bukkit.overlay.slot.event

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayDsl
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * 与某槽位相关的覆盖层事件。
 *
 * 标记 [PlayerOverlayDsl]：槽位事件是 OverlaySlotScope DSL 各回调 lambda 的接收者。与 OverlaySlotScope
 * 同标记后，回调体内对外层 builder 成员的隐式访问被禁止——如 `onAction { item(...) }` 不再编译通过
 * （handler 于运行期执行，运行期调用构建期的声明函数会改写共享 spec，是必须堵住的误用）。
 */
@PlayerOverlayDsl
interface OverlaySlotEvent : OverlayEvent {
    val index: Int
}

/**
 * 玩家对某覆盖槽位的一次主动操作（密封层级）：在
 * [com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.OverlaySlotScope.onAction]
 * 的块内用 `when (this)` 穷尽区分来源——
 * [Click]：玩家背包窗口内的点击；[Interact]：手持该槽物品在世界中的左/右键交互。
 */
sealed interface OverlaySlotActionEvent : OverlaySlotEvent {

    val player: Player

    /** 玩家在背包窗口内点击某覆盖槽位（带 Bukkit [ClickType]）。 */
    class Click(
        override val overlay: PlayerOverlay,
        override val index: Int,
        override val player: Player,
        val clickType: ClickType,
    ) : OverlaySlotActionEvent

    /** 玩家手持某覆盖槽位物品挥动/使用（左/右键）。 */
    class Interact(
        override val overlay: PlayerOverlay,
        override val index: Int,
        override val player: Player,
        val action: Action,
    ) : OverlaySlotActionEvent {
        enum class Action {
            LEFT_CLICK,
            RIGHT_CLICK,
        }
    }
}
