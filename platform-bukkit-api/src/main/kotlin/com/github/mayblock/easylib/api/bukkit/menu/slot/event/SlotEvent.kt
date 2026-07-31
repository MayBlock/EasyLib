package com.github.mayblock.easylib.api.bukkit.menu.slot.event

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotDsl
import org.bukkit.entity.Player

/**
 * 槽位事件根接口。
 *
 * 标记 [SlotDsl]：槽位事件是 SlotScope DSL 各回调 lambda 的接收者。与 SlotScope 同标记后，
 * 回调体内对外层 builder 成员的隐式访问被禁止——如 `onClick { item(...) }` 不再编译通过
 * （handler 于运行期执行，运行期调用构建期的声明函数会改写共享 spec，是必须堵住的误用）。
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