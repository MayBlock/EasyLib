package com.github.mayblock.easylib.api.bukkit.overlay.dsl

import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.OverlaySlotScope

@DslMarker
internal annotation class PlayerOverlayDsl

/**
 * 覆盖层整体 DSL：按 index/range 声明覆盖槽位。
 * [slot] 的 item 以**拷贝**存入（metadata 应用于该拷贝）：声明后继续改动原对象不影响覆盖层。
 */
@PlayerOverlayDsl
interface PlayerOverlayScope {

    fun slot(
        index: Int,
        block: (OverlaySlotScope.() -> Unit)? = null,
    )
    fun slot(
        range: IntRange,
        block: (OverlaySlotScope.() -> Unit)? = null,
    )
}
