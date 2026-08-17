package com.github.mayblock.easylib.api.bukkit.menu.slot.event

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.base.api.event.Event
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 玩家把菜单槽位中的物品取出（拿到光标、shift 移入背包或丢出）时派发。
 *
 * **契约：默认 `isCancelled = true`（拒绝）**，处理器须显式改为 `false` 才放行本次取出；
 * 多个处理器按 Priority 升序执行，后者可覆盖前者。放行时物品搬运由 Bukkit 原生完成，
 * **订阅方只把关/观察，不要再手动给予物品**。详见 `docs/menus.md`「取出 / 放入把关」。
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
 * **契约：默认 `isCancelled = true`（拒绝）**，处理器须显式改为 `false` 才放行本次放入；
 * 多个处理器按 Priority 升序执行，后者可覆盖前者。放行时物品搬运由 Bukkit 原生完成
 * （shift 入菜单例外：由引擎向各放行槽分发并扣减来源格），**订阅方只把关/观察，不要再手动扣除物品**。
 * 详见 `docs/menus.md`「取出 / 放入把关」。
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
