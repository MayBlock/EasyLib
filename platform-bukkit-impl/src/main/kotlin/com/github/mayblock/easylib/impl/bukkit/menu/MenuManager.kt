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
import com.github.mayblock.easylib.packetevents.PacketManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import java.io.Closeable

class MenuManager(
    private val taskScheduler: TaskScheduler,
    private val packetManager: PacketManager<*>,
    plugin: Plugin,
) : MenuFactory, MenuRegistry, Closeable {

    private val menus = mutableListOf<Menu>()
    private val activeMenus = mutableMapOf<Player, Menu>()
    private val listener = MenuInteractionListener(this).also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }

    override fun getActiveMenu(player: Player): Menu? = activeMenus[player]
    override fun hasActiveMenu(player: Player): Boolean = activeMenus.containsKey(player)
    override fun getViewers(menu: Menu): Set<Player> = activeMenus.filterValues { it === menu }.keys

    override fun createChestMenu(
        type: ChestMenuType,
        hidePlayerInventory: Boolean,
        block: PageableChestMenuScope.() -> Unit
    ): ChestMenu =
        PageableChestMenuBuilder(type) { title, slots ->
            // 注册塞进工厂 lambda：分页菜单的每一页都经由本工厂创建，
            // 因此每页都会在这里被登记，而不仅仅是 build() 返回的第 1 页。
            RealChestMenu(
                taskScheduler,
                packetManager,
                title,
                type,
                slots,
                hidePlayerInventory,
                onDestroyed = ::forget
            ).also(::register)
        }.apply(block)
            .build()

    /** 登记菜单，并通过其事件源跟踪活跃观察者（开/关菜单驱动 [activeMenus]）。 */
    private fun <M : Menu> register(menu: M): M {
        // 经 BukkitMenu 接口赋 owner：manager 对具体 UI 类型（chest/铁砧/……）零感知。
        (menu as? BukkitMenu)?.owner = this
        menu.on {
            on<MenuOpenEvent> { activeMenus[player] = menu }
            on<MenuCloseEvent> { if (activeMenus[player] === menu) activeMenus.remove(player) }
        }
        return menu.also(menus::add)
    }

    /** 菜单 destroy() 时的回调：撤销登记，避免 [menus]/[activeMenus] 只增不减地累积已销毁的菜单。 */
    private fun forget(menu: Menu) {
        menus.remove(menu)
        activeMenus.entries.removeIf { it.value === menu }
    }

    override fun close() {
        // destroy() 会经 onDestroyed 回调触发 forget()，进而修改 menus 本身；
        // 必须遍历快照，否则会在 forEach 过程中并发结构性修改 menus 导致 CME。
        menus.toList().forEach { it.destroy() }
        HandlerList.unregisterAll(listener)
        menus.clear()
        activeMenus.clear()
    }
}
