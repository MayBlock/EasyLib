package com.github.mayblock.easylib.platform.bukkit.api.overlay

import com.github.mayblock.easylib.platform.bukkit.api.overlay.dsl.PlayerOverlayScope
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext

interface PlayerOverlayFactory {
    /** 按 DSL 构建覆盖层；运行期更新和事件回调默认使用 Sync。构建块在调用线程执行。 */
    fun create(block: PlayerOverlayScope.() -> Unit): PlayerOverlay

    /** 为更新和事件回调显式选择执行上下文；同一覆盖层的回调保持串行。 */
    fun create(context: BukkitExecutionContext, block: PlayerOverlayScope.() -> Unit): PlayerOverlay
}
