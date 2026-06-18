package com.github.mayblock.easylib.impl.bukkit.menu.chest.builder

import com.github.mayblock.easylib.api.bukkit.menu.InventoryMenuItem
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.chest.dsl.ChestMenuScope
import com.github.mayblock.easylib.api.bukkit.menu.chest.dsl.PageableChestMenuScope
import net.kyori.adventure.text.Component
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

internal class PageableChestMenuBuilder(
    override val type: ChestMenuType,
    private val factory: (
        title: Component, slots: List<InventoryMenuItem?>
    ) -> ChestMenu
) : PageableChestMenuScope {
    private val size = type.size
    private val pages = mutableListOf<ChestMenuBuilder>()

    override fun page(title: Component, block: ChestMenuScope.() -> Unit) {
        pages.add(ChestMenuBuilder(type, title, factory).apply(block))
    }

    private var nextPageItem: Pair<Int, ItemStack> = Pair(
        size - 4,
        ItemStack(Material.ARROW).apply {
            this.itemMeta = itemMeta!!.apply {
                setDisplayName("${ChatColor.GREEN}Next Page")
            }
        }
    )
    private var previousPageItem: Pair<Int, ItemStack> = Pair(
        size - 6,
        ItemStack(Material.ARROW).apply {
            this.itemMeta = itemMeta!!.apply {
                setDisplayName("${ChatColor.GREEN}Previous Page")
            }
        }
    )

    override fun setNextPageItem(
        item: ItemStack,
        slot: Int,
        metadata: ItemMeta.() -> Unit
    ) {
        nextPageItem = Pair(slot, item.also {
            it.itemMeta = it.itemMeta?.apply(metadata)
        })
    }

    override fun setPreviousPageItem(
        item: ItemStack,
        slot: Int,
        metadata: ItemMeta.() -> Unit
    ) {
        previousPageItem = Pair(slot, item.also {
            it.itemMeta = it.itemMeta?.apply(metadata)
        })
    }

    fun build(): ChestMenu {
        require(pages.isNotEmpty()) { "page cannot be empty" }
        val builtMenus = mutableListOf<ChestMenu>()
        if (pages.size > 1) {
            pages.forEachIndexed { i, page ->
                page.title = page.title.append(Component.text(" (${i + 1}/${pages.size})"))
                if (i + 1 < pages.size) {
                    val (slot, item) = nextPageItem
                    page.slot(slot, item) { player, _ ->
                        builtMenus[i + 1].open(player)
                    }
                }
                if (i - 1 >= 0) {
                    val (slot, item) = previousPageItem
                    page.slot(slot, item) { player, _ ->
                        builtMenus[i - 1].open(player)
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