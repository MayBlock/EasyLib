package com.github.mayblock.easylib.impl.bukkit.overlay.slot

import com.github.mayblock.easylib.impl.bukkit.util.SlotDisplayMap
import com.github.retrooper.packetevents.protocol.item.ItemStack
import org.bukkit.entity.Player

/**
 * 覆盖层的按接收者取物品视图：把共享基底（[SlotMap]）与 per-viewer 显示层（[SlotDisplayMap]）
 * 合成为单一只读入口，供 [com.github.mayblock.easylib.impl.bukkit.overlay.transport.OverlayTransport]
 * 实现使用。
 *
 * 取物品统一公式：`display.lookup(uuid, slot) ?: map.packetItem(slot)`。
 *
 * **与菜单侧的实质差异**：菜单改写层未命中显示层时透传真实容器；覆盖层未命中时必须回落到
 * **共享基底**，绝不能透传玩家的真实背包——否则遮罩当场穿帮。
 */
internal class OverlayView(
    private val map: SlotMap,
    private val display: SlotDisplayMap,
) {
    /** 该槽是否在构建时被声明（与显示层无关：声明是共享事实）。 */
    fun isDeclared(index: Int): Boolean = map[index] != null

    /**
     * 面向 [player] 的该槽 packet 物品；未声明返回 [ItemStack.EMPTY]。
     *
     * 前提（未做运行期校验）：显示层条目只会存在于已声明的槽——更新循环只对声明槽集合求值，
     * 因此不会为未声明槽写入显示层。这里不重复判断 [isDeclared]，以免在热路径上做冗余查表。
     */
    fun packetItem(player: Player, index: Int): ItemStack =
        display.lookup(player.uniqueId, index)?.packetItem ?: map.packetItem(index)

    /** 面向 [player] 的 `[0, size)` 全量 packet 物品，未声明处为 null（用于 WindowItems/ContainerItems）。 */
    fun packetItems(player: Player, size: Int): List<ItemStack?> = List(size) { index ->
        display.lookup(player.uniqueId, index)?.packetItem ?: map[index]?.packetItem()
    }
}
