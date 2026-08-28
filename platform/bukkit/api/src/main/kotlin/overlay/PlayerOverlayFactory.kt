package com.github.mayblock.easylib.platform.bukkit.api.overlay

import com.github.mayblock.easylib.platform.bukkit.api.overlay.dsl.PlayerOverlayScope

interface PlayerOverlayFactory {
    /** 按 DSL 构建一个玩家背包覆盖层。 */
    fun create(block: PlayerOverlayScope.() -> Unit): PlayerOverlay
}
