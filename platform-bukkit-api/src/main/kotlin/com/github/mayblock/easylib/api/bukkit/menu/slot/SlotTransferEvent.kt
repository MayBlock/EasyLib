package com.github.mayblock.easylib.api.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.event.Event
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 玩家把菜单槽位中的物品取出（拿到光标、shift 移入背包或丢出）时派发（可取消）。
 *
 * 物品移动由 Bukkit 原生完成：未取消时原生点击照常执行，**订阅方只把关/观察，不要再手动给予物品**
 * （会导致复制）；取消则整个原生点击被取消，物品维持原状。
 *
 * @param index 物品来源的菜单槽位
 * @param item 被取走物品的副本
 * @param targetSlot 目标真实背包槽位（best-effort）；原生取出通常无法得知，取不到时为 -1
 */
class SlotTakeEvent(
    menu: Menu,
    index: Int,
    player: Player,
    val item: ItemStack,
    val targetSlot: Int,
    override var isCancelled: Boolean = false,
) : SlotClickEvent(menu, index, player), Event.Cancellable

/**
 * 玩家把物品放入菜单槽位时派发（可取消）。
 *
 * 物品移动通常由 Bukkit 原生完成（光标放置/拖拽），**订阅方只把关/观察，不要再手动扣除物品**；
 * 唯一例外是 shift-入菜单：引擎取消原生事件后自行向 placeable 槽分发、扣减来源格并调用
 * `player.updateInventory()`。取消语义：光标/拖拽路径取消整个原生事件；shift 多目标分发仅跳过被取消的槽。
 *
 * @param index 放入的目标菜单槽位
 * @param item 待放入物品的副本（shift/拖拽多目标分发时为该槽对应的量；光标放置时为整个光标堆叠）
 * @param sourceSlot 物品来源的真实背包槽位（best-effort）；仅 shift-入菜单可得，其余路径为 -1
 */
class SlotPlaceEvent(
    menu: Menu,
    index: Int,
    player: Player,
    val item: ItemStack,
    val sourceSlot: Int,
    override var isCancelled: Boolean = false,
) : SlotClickEvent(menu, index, player), Event.Cancellable
