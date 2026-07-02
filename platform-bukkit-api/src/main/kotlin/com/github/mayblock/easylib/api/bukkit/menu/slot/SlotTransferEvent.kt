package com.github.mayblock.easylib.api.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.event.Event
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 玩家把菜单槽位中的物品取出到自己背包时派发（可取消）。
 *
 * 引擎只维护虚拟层：未取消时，**真实物品的给予由订阅方负责**（如 `player.inventory.addItem(item)`），
 * 引擎随后会重同步客户端视觉。取消则虚拟光标维持原状。
 *
 * @param index 物品来源的菜单槽位
 * @param item 被取走物品的副本
 * @param targetSlot 玩家点击的目标真实背包槽位（Bukkit `PlayerInventory` 语义 0-35），供回调精确放置
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
 * 玩家把自己背包的物品放入菜单槽位时派发（可取消）。
 *
 * 引擎只维护虚拟层：未取消时，**真实物品的扣除由订阅方负责**（按 [item] 的数量从 [sourceSlot] 扣除），
 * 引擎随后提交虚拟槽位并调用 `player.updateInventory()` 渲染扣除结果。取消则回滚重刷。
 *
 * @param index 放入的目标菜单槽位
 * @param item 待放入物品的副本（右键放置时数量可能小于光标持有量）
 * @param sourceSlot 物品来源的真实背包槽位（Bukkit `PlayerInventory` 语义 0-35）
 */
class SlotPlaceEvent(
    menu: Menu,
    index: Int,
    player: Player,
    val item: ItemStack,
    val sourceSlot: Int,
    override var isCancelled: Boolean = false,
) : SlotClickEvent(menu, index, player), Event.Cancellable
