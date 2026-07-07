package com.github.mayblock.easylib.api.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope

interface PlayerOverlayFactory {
    /** 按 DSL 构建一个玩家背包覆盖层。 */
    fun create(builder: PlayerOverlayScope.() -> Unit): PlayerOverlay
}
