package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.OverlaySlotScope
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** 收集 [PlayerOverlayScope] 声明为 `Map<Int, OverlaySlotSpec>`，交由 [factory] 造出覆盖层。 */
internal class PlayerOverlayBuilder(
    private val factory: (slots: Map<Int, OverlaySlotSpec>) -> PlayerOverlay,
) : PlayerOverlayScope {

    private val size: Int = PlayerOverlay.OVERLAY_SIZE
    private val slots = mutableMapOf<Int, OverlaySlotSpec>()

    override fun slot(
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (OverlaySlotScope.() -> Unit)?,
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size)" }
        slots[index] = buildSlot(item, metadata, block)
    }

    override fun slot(
        range: IntRange,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (OverlaySlotScope.() -> Unit)?,
    ) {
        require(range.first >= 0 && range.last < size) { "slot must be in range [0, $size)" }
        val slot = buildSlot(item, metadata, block)
        range.forEach { slots[it] = slot }
    }

    private fun buildSlot(
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (OverlaySlotScope.() -> Unit)?,
    ): OverlaySlotSpec {
        return OverlaySlotBuilder()
            .apply { block?.invoke(this) }
            .build(item.also { item.itemMeta = item.itemMeta?.also(metadata) })
    }

    fun build(): PlayerOverlay = factory(slots)
}
