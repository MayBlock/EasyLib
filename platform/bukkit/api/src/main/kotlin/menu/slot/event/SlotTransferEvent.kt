package com.github.mayblock.easylib.api.bukkit.menu.slot.event

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.base.api.event.Event
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 玩家把菜单槽位中的物品取出（拿到光标、shift 移入背包或丢出）时派发。
 *
 * **契约：事件默认 `isCancelled = true`（拒绝）。** 已声明的槽位一律派发本事件，
 * 是否放行完全交给运行期的处理器决定——handler 需显式 `isCancelled = false` 才放行本次取出；
 * 多个 handler（含 DSL `onTake` 与外部经 `menu.on { on<SlotTakeEvent>{...} }` 订阅的处理器，
 * 二者同在总线上、无优先级差异）按 [com.github.mayblock.easylib.base.api.util.Priority] 升序依次执行，
 * 后执行者可覆盖先执行者的决定（既可以放行后又取消，也可以取消后又放行）；派发结束时仍
 * `isCancelled` 则取消 Bukkit 原生操作。不声明任何处理器 ⇒ 没人放行 ⇒ 始终拒绝（等价旧
 * `movable = false`）；`onTake { isCancelled = false }` ⇒ 无条件放行，且可按 player/物品等条件
 * 动态决定，表达力强于旧的静态布尔开关。
 *
 * 物品移动由 Bukkit 原生完成：放行（未取消）时原生点击照常执行，**订阅方只把关/观察，
 * 不要再手动给予物品**（会导致复制）；仍取消则整个原生点击被取消，物品维持原状。
 *
 * @param index 物品来源的菜单槽位
 * @param item 被取走物品的副本
 */
class SlotTakeEvent(
    menu: Menu,
    index: Int,
    player: Player,
    val item: ItemStack,
    override var isCancelled: Boolean = true,
) : SlotClickEvent(menu, index, player), Event.Cancellable

/**
 * 玩家把物品放入菜单槽位时派发。
 *
 * **契约：事件默认 `isCancelled = true`（拒绝）。** 已声明的槽位一律派发本事件，
 * 是否放行完全交给运行期的处理器决定——handler 需显式 `isCancelled = false` 才放行本次放入；
 * 多个 handler（含 DSL `onPlace` 与外部经 `menu.on { on<SlotPlaceEvent>{...} }` 订阅的处理器，
 * 二者同在总线上、无优先级差异）按 [com.github.mayblock.easylib.base.api.util.Priority] 升序依次执行，
 * 后执行者可覆盖先执行者的决定；派发结束时仍 `isCancelled` 则取消 Bukkit 原生操作。不声明任何
 * 处理器 ⇒ 没人放行 ⇒ 始终拒绝（等价旧 `placeable = false`）；`onPlace { isCancelled = false }`
 * ⇒ 无条件放行，且可按条件动态决定。
 *
 * 物品移动通常由 Bukkit 原生完成（光标放置/拖拽），**订阅方只把关/观察，不要再手动扣除物品**；
 * 唯一例外是 shift-入菜单：引擎取消原生事件后自行向声明了 onPlace 放行处理器的槽分发、
 * 扣减来源格并调用 `player.updateInventory()`。取消语义：光标/拖拽路径取消整个原生事件；
 * shift 多目标分发仅跳过被取消的槽。
 *
 * @param index 放入的目标菜单槽位
 * @param item 待放入物品的副本（shift/拖拽多目标分发时为该槽对应的量；光标放置时为整个光标堆叠）
 */
class SlotPlaceEvent(
    menu: Menu,
    index: Int,
    player: Player,
    val item: ItemStack,
    override var isCancelled: Boolean = true,
) : SlotClickEvent(menu, index, player), Event.Cancellable
