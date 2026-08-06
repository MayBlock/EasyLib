package com.github.mayblock.easylib.base.impl.bukkit.item

import com.github.mayblock.easylib.api.bukkit.item.CustomItemClick
import com.github.mayblock.easylib.api.bukkit.item.CustomItemConsume
import com.github.mayblock.easylib.api.bukkit.item.CustomItemDrop
import com.github.mayblock.easylib.api.bukkit.item.CustomItemInteraction

/** 一个自定义物品的全部事件回调；null 表示未注册（各钩子的默认行为见 API KDoc）。 */
internal class CustomItemHandlers(
    val interact: (CustomItemInteraction.() -> Unit)?,
    val click: (CustomItemClick.() -> Unit)?,
    val drop: (CustomItemDrop.() -> Unit)?,
    val consume: (CustomItemConsume.() -> Unit)?,
)
