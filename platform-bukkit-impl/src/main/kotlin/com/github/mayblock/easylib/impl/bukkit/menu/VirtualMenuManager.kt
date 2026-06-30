package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuFactory
import com.github.mayblock.easylib.api.bukkit.menu.MenuOpenEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuRegistry
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.PageableChestMenuScope
import com.github.mayblock.easylib.api.bukkit.menu.type.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.player.dsl.PlayerMenuScope
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.VirtualChestMenu
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder.PageableChestMenuBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.type.player.VirtualPlayerInventoryMenu
import com.github.mayblock.easylib.impl.bukkit.menu.type.player.builder.PlayerMenuBuilder
import org.bukkit.entity.Player
import java.io.Closeable

class VirtualMenuManager(private val taskScheduler: TaskScheduler) : MenuFactory, MenuRegistry, Closeable {

    private val menus = mutableListOf<Menu>()
    private val activeMenus = mutableMapOf<Player, Menu>()

    override fun getActiveMenu(player: Player): Menu? = activeMenus[player]
    override fun hasActiveMenu(player: Player): Boolean = activeMenus.containsKey(player)
    override fun getViewers(menu: Menu): Set<Player> = activeMenus.filterValues { it === menu }.keys

    override fun createPlayerInventoryMenu(builder: PlayerMenuScope.() -> Unit): PlayerInventoryMenu =
        register(
            PlayerMenuBuilder { slots ->
                VirtualPlayerInventoryMenu(taskScheduler, slots)
            }.apply(builder).build()
        )

    override fun createChestMenu(type: ChestMenuType, builder: PageableChestMenuScope.() -> Unit): com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu =
        register(
            PageableChestMenuBuilder(type) { title, slots ->
                VirtualChestMenu(taskScheduler, title, type, slots)
            }.apply(builder).build()
        )

    /** 登记菜单，并通过其事件源跟踪活跃观察者（开/关菜单驱动 [activeMenus]）。 */
    private fun <M : Menu> register(menu: M): M {
        menus += menu
        menu.on {
            on<MenuOpenEvent> { activeMenus[player] = menu }
            on<MenuCloseEvent> { if (activeMenus[player] === menu) activeMenus.remove(player) }
        }
        return menu
    }

    override fun close() {
        menus.forEach { it.destroy() }
        menus.clear()
        activeMenus.clear()
    }
}
