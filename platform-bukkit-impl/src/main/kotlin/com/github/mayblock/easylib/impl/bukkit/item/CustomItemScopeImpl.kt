package com.github.mayblock.easylib.impl.bukkit.item

import com.github.mayblock.easylib.api.bukkit.item.CustomItemBlockPlace
import com.github.mayblock.easylib.api.bukkit.item.CustomItemClick
import com.github.mayblock.easylib.api.bukkit.item.CustomItemDrop
import com.github.mayblock.easylib.api.bukkit.item.CustomItemInteraction
import com.github.mayblock.easylib.api.bukkit.item.CustomItemScope
import org.bukkit.inventory.meta.ItemMeta

internal class CustomItemScopeImpl : CustomItemScope {

    val metadata = mutableListOf<ItemMeta.() -> Unit>()
    private var interactHandler: (CustomItemInteraction.() -> Unit)? = null
    private var clickHandler: (CustomItemClick.() -> Unit)? = null
    private var dropHandler: (CustomItemDrop.() -> Unit)? = null
    private var placeHandler: (CustomItemBlockPlace.() -> Unit)? = null

    override fun meta(block: ItemMeta.() -> Unit) { metadata += block }
    override fun onInteract(block: CustomItemInteraction.() -> Unit) { interactHandler = block }
    override fun onInventoryClick(block: CustomItemClick.() -> Unit) { clickHandler = block }
    override fun onDrop(block: CustomItemDrop.() -> Unit) { dropHandler = block }
    override fun onBlockPlace(block: CustomItemBlockPlace.() -> Unit) { placeHandler = block }

    fun handlers() = CustomItemHandlers(interactHandler, clickHandler, dropHandler, placeHandler)
}
