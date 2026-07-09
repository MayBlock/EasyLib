package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuFactory
import com.github.mayblock.easylib.api.bukkit.menu.MenuOpenEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuRegistry
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.PageableChestMenuScope
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder.PageableChestMenuBuilder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import java.io.Closeable

class MenuManager(
    private val taskScheduler: TaskScheduler,
    plugin: Plugin,
) : MenuFactory, MenuRegistry, Closeable {

    private val menus = mutableListOf<Menu>()
    private val activeMenus = mutableMapOf<Player, Menu>()
    private val listener = MenuInteractionListener().also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }

    override fun getActiveMenu(player: Player): Menu? = activeMenus[player]
    override fun hasActiveMenu(player: Player): Boolean = activeMenus.containsKey(player)
    override fun getViewers(menu: Menu): Set<Player> = activeMenus.filterValues { it === menu }.keys

    override fun createChestMenu(
        type: ChestMenuType,
        hidePlayerInventory: Boolean,
        builder: PageableChestMenuScope.() -> Unit
    ): ChestMenu =
        PageableChestMenuBuilder(type) { title, slots ->
            RealChestMenu(taskScheduler, title, type, slots, hidePlayerInventory)
        }.apply(builder)
            .build()
            .let(::register)

    /** 登记菜单，并通过其事件源跟踪活跃观察者（开/关菜单驱动 [activeMenus]）。 */
    private fun <M : Menu> register(menu: M): M {
        menu.on {
            on<MenuOpenEvent> { activeMenus[player] = menu }
            on<MenuCloseEvent> { if (activeMenus[player] === menu) activeMenus.remove(player) }
        }
        return menu.also(menus::add)
    }

    override fun close() {
        menus.forEach { it.destroy() }
        HandlerList.unregisterAll(listener)
        menus.clear()
        activeMenus.clear()
    }
}
