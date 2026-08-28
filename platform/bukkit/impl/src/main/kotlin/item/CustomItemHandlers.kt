package com.github.mayblock.easylib.platform.bukkit.impl.item

import com.github.mayblock.easylib.platform.bukkit.api.item.CustomItemClick
import com.github.mayblock.easylib.platform.bukkit.api.item.CustomItemConsume
import com.github.mayblock.easylib.platform.bukkit.api.item.CustomItemDrop
import com.github.mayblock.easylib.platform.bukkit.api.item.CustomItemInteraction

/** 一个自定义物品的全部事件回调；null 表示未注册（各钩子的默认行为见 API KDoc）。 */
internal class CustomItemHandlers(
    val interact: (CustomItemInteraction.() -> Unit)?,
    val click: (CustomItemClick.() -> Unit)?,
    val drop: (CustomItemDrop.() -> Unit)?,
    val consume: (CustomItemConsume.() -> Unit)?,
)
