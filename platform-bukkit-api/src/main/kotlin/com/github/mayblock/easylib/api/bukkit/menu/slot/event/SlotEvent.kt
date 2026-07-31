package com.github.mayblock.easylib.api.bukkit.menu.slot.event

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotDsl
import org.bukkit.entity.Player

/**
 * 槽位事件根接口。
 *
 * 标记 [SlotDsl]：槽位事件是 SlotScope DSL 各回调 lambda 的接收者。与 SlotScope 同标记后，
 * 回调体内对外层 builder 成员的隐式访问被禁止——`item(Material.X)` 在回调体内
 * 解析到顶层物品工厂，而非 SlotScope 的槽位物品声明函数（两者同名，成员本会遮蔽顶层）。
 */
@SlotDsl
interface SlotEvent: MenuEvent {
    val index: Int
}

open class SlotClickEvent(
    final override val menu: Menu,
    final override val index: Int,
    val player: Player
): SlotEvent