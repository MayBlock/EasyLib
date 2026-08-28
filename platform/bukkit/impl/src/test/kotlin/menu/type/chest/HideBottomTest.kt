package com.github.mayblock.easylib.platform.bukkit.impl.menu.type.chest

import kotlin.test.Test
import kotlin.test.assertEquals

class HideBottomTest {
    @Test fun `隐藏区为容器尺寸到窗口末尾（36 格玩家背包）`() {
        assertEquals(27 until 63, hiddenBottomIndices(27, 63)) // 9x3：27 容器 + 36 背包
        assertEquals(54 until 90, hiddenBottomIndices(54, 90)) // 9x6
    }
}
