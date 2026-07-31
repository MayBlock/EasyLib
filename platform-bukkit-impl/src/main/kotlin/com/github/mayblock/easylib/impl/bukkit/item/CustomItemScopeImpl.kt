package com.github.mayblock.easylib.impl.bukkit.item

import com.github.mayblock.easylib.api.bukkit.item.*
import org.bukkit.inventory.meta.ItemMeta

internal data class MetaRule(val type: Class<out ItemMeta>, val block: ItemMeta.() -> Unit)
internal class CustomItemScopeImpl : CustomItemScope {

    val metadata = mutableListOf<MetaRule>()
    private var interactHandler: (CustomItemInteraction.() -> Unit)? = null
    private var clickHandler: (CustomItemClick.() -> Unit)? = null
    private var dropHandler: (CustomItemDrop.() -> Unit)? = null
    private var placeHandler: (CustomItemBlockPlace.() -> Unit)? = null

    override fun <T : ItemMeta> meta(type: Class<out T>, block: T.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        metadata.add(MetaRule(type, block as ItemMeta.() -> Unit))
    }
    override fun onInteract(block: CustomItemInteraction.() -> Unit) { interactHandler = block }
    override fun onInventoryClick(block: CustomItemClick.() -> Unit) { clickHandler = block }
    override fun onDrop(block: CustomItemDrop.() -> Unit) { dropHandler = block }
    override fun onBlockPlace(block: CustomItemBlockPlace.() -> Unit) { placeHandler = block }

    fun handlers() = CustomItemHandlers(interactHandler, clickHandler, dropHandler, placeHandler)
}
