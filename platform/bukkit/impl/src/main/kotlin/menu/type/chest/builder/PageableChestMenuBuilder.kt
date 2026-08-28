package com.github.mayblock.easylib.platform.bukkit.impl.menu.type.chest.builder

import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.dsl.ChestMenuScope
import com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.dsl.PageableChestMenuScope
import com.github.mayblock.easylib.platform.bukkit.impl.menu.slot.SlotSpec
import com.github.mayblock.easylib.platform.bukkit.impl.util.meta
import com.github.mayblock.easylib.platform.bukkit.impl.util.stack
import net.kyori.adventure.text.Component
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

internal class PageableChestMenuBuilder(
    override val type: ChestMenuType,
    private val factory: (
        title: Component, slots: Map<Int, SlotSpec>
    ) -> ChestMenu
) : PageableChestMenuScope {
    private val size = type.size
    private val pages = mutableListOf<ChestMenuBuilder>()

    override fun page(title: Component, block: ChestMenuScope.() -> Unit) {
        pages.add(ChestMenuBuilder(type, title, factory).apply(block))
    }

    private var nextPageItem: Pair<Int, ItemStack> = Pair(
        size - 4,
        stack(Material.ARROW).meta {
            setDisplayName("${ChatColor.GREEN}Next Page")
        }
    )
    private var previousPageItem: Pair<Int, ItemStack> = Pair(
        size - 6,
        stack(Material.ARROW).meta {
            setDisplayName("${ChatColor.GREEN}Previous Page")
        }
    )

    override fun setNextPageItem(
        item: ItemStack,
        slot: Int,
        metadata: (ItemMeta.() -> Unit)?
    ) {
        // 先 clone 再改 meta：避免直接篡改调用方传入的 item 实例。
        nextPageItem = Pair(slot, item.clone().also { item ->
            metadata?.let(item::meta)
        })
    }

    override fun setPreviousPageItem(
        item: ItemStack,
        slot: Int,
        metadata: (ItemMeta.() -> Unit)?
    ) {
        // 先 clone 再改 meta：避免直接篡改调用方传入的 item 实例。
        previousPageItem = Pair(slot, item.clone().also { item ->
            metadata?.let(item::meta)
        })
    }

    fun build(): ChestMenu {
        require(pages.isNotEmpty()) { "page cannot be empty" }
        val builtMenus = mutableListOf<ChestMenu>()
        if (pages.size > 1) {
            pages.forEachIndexed { i, page ->
                page.title = page.title.append(Component.text(" (${i + 1}/${pages.size})"))
                if (i + 1 < pages.size) {
                    val (index, item) = nextPageItem
                    require(!page.hasSlot(index)) { "slot $index is reserved for page navigation" }
                    page.slot(index) {
                        item(item)
                        onClick {
                            builtMenus[i + 1].open(player)
                        }
                    }
                }
                if (i - 1 >= 0) {
                    val (index, item) = previousPageItem
                    require(!page.hasSlot(index)) { "slot $index is reserved for page navigation" }
                    page.slot(index) {
                        item(item)
                        onClick {
                            builtMenus[i - 1].open(player)
                        }
                    }
                }
            }
        }
        pages.forEach {
            builtMenus.add(it.build())
        }
        return builtMenus[0]
    }
}