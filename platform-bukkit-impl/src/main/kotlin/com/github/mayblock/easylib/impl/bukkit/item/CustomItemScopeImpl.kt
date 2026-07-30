package com.github.mayblock.easylib.impl.bukkit.item

import com.github.mayblock.easylib.api.bukkit.item.CustomItemClick
import com.github.mayblock.easylib.api.bukkit.item.CustomItemInteraction
import com.github.mayblock.easylib.api.bukkit.item.CustomItemScope
import org.bukkit.inventory.meta.ItemMeta

internal class CustomItemScopeImpl : CustomItemScope {

    val metadata = mutableListOf<ItemMeta.() -> Unit>()
    var interactHandler: (CustomItemInteraction.() -> Unit)? = null
        private set
    var clickHandler: (CustomItemClick.() -> Unit)? = null
        private set

    override fun meta(block: ItemMeta.() -> Unit) { metadata += block }
    override fun onInteract(block: CustomItemInteraction.() -> Unit) { interactHandler = block }
    override fun onInventoryClick(block: CustomItemClick.() -> Unit) { clickHandler = block }
}
