package com.github.mayblock.easylib.base.impl.bukkit.menu.type.chest

/** 容器窗口中需屏蔽的玩家背包区窗口 slot 范围（容器尺寸之后的 36 格）。 */
internal fun hiddenBottomIndices(menuSize: Int, windowSize: Int): IntRange = menuSize until windowSize
