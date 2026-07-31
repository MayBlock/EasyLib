package com.github.mayblock.easylib.api.bukkit.item

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

/**
 * 自定义物品注册表。身份识别基于物品 PDC 中持久化的 [NamespacedKey] 字符串，
 * 重启后以相同 key 重新 [define] 即可继续识别旧物品栈。
 */
interface CustomItemRegistry {

    /**
     * 定义并注册一个自定义物品。
     *
     * 注意：可放置材质的自定义物品**无法被放置为方块**（库在交互阶段一律阻止原版放置，
     * 详见 [CustomItemScope.onInteract]）；「放置类」交互需求可在 onInteract 中自行实现。
     * @throws IllegalArgumentException 若 [key] 已注册。
     */
    fun define(type: Material, key: NamespacedKey, block: (CustomItemScope.() -> Unit)? = null): CustomItem

    fun get(key: NamespacedKey): CustomItem?

    /** 按物品栈的 PDC 身份反查已注册的自定义物品。 */
    fun fromStack(stack: ItemStack?): CustomItem?

    fun isRegistered(key: NamespacedKey): Boolean

    /** 注销后相关回调立即停止触发。 */
    fun unregister(key: NamespacedKey): Boolean

    fun unregisterAll()
}