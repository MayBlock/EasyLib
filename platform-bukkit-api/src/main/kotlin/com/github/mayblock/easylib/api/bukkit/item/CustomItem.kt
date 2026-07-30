package com.github.mayblock.easylib.api.bukkit.item

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 一个已注册的自定义物品。身份由 [key] 决定并随物品栈持久化（PDC），跨重启稳定。
 */
interface CustomItem {

    val key: NamespacedKey
    val type: Material

    /** 创建一份带身份标识的物品栈拷贝。 */
    fun createStack(amount: Int = 1): ItemStack

    /** 判断给定物品栈是否是本自定义物品（按 PDC 身份判断，与外观无关）。 */
    fun matches(stack: ItemStack?): Boolean

    /** 发放物品；背包放不下的溢出部分掉落在玩家脚下。 */
    fun give(player: Player, amount: Int = 1)

    /**
     * 从玩家背包扣除 [amount] 个本物品。
     * 扫描范围覆盖玩家整个背包，包括盔甲槽位与副手槽位。
     * @return 足量并扣除成功返回 true；不足则**不做任何扣除**并返回 false。
     */
    fun take(player: Player, amount: Int = 1): Boolean
}