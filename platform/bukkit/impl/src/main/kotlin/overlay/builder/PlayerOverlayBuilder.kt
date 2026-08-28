package com.github.mayblock.easylib.platform.bukkit.impl.overlay.builder

import com.github.mayblock.easylib.platform.bukkit.api.overlay.PlayerOverlay
import com.github.mayblock.easylib.platform.bukkit.api.overlay.dsl.PlayerOverlayScope
import com.github.mayblock.easylib.platform.bukkit.api.overlay.slot.dsl.OverlaySlotScope
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.OverlaySlotSpec

/** 收集 [PlayerOverlayScope] 声明为 `Map<Int, OverlaySlotSpec>`，交由 [factory] 造出覆盖层。 */
internal class PlayerOverlayBuilder(
    private val factory: (slots: Map<Int, OverlaySlotSpec>) -> PlayerOverlay,
) : PlayerOverlayScope {

    private val size: Int = PlayerOverlay.OVERLAY_SIZE
    private val slots = mutableMapOf<Int, OverlaySlotSpec>()

    override fun slot(
        index: Int,
        block: (OverlaySlotScope.() -> Unit)?,
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size)" }
        slots[index] = buildSlot(block)
    }

    override fun slot(
        range: IntRange,
        block: (OverlaySlotScope.() -> Unit)?,
    ) {
        require(range.first >= 0 && range.last < size) { "slot must be in range [0, $size)" }
        val slot = buildSlot(block)
        range.forEach { slots[it] = slot }
    }

    private fun buildSlot(
        block: (OverlaySlotScope.() -> Unit)?,
    ): OverlaySlotSpec {
        // 基于副本应用 metadata：不原地修改调用者的 item，spec 持有自己的对象。
        return OverlaySlotBuilder()
            .apply { block?.invoke(this) }
            .build()
    }

    fun build(): PlayerOverlay = factory(slots)
}
