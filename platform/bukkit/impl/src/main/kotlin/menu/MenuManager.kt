package com.github.mayblock.easylib.base.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.*
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.PageableChestMenuScope
import com.github.mayblock.easylib.base.api.event.on
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.util.Priority
import com.github.mayblock.easylib.base.impl.bukkit.menu.listener.MenuInteractionListener
import com.github.mayblock.easylib.base.impl.bukkit.menu.type.chest.RealChestMenu
import com.github.mayblock.easylib.base.impl.bukkit.menu.type.chest.builder.PageableChestMenuBuilder
import com.github.mayblock.easylib.packetevents.api.PacketManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.inventory.InventoryHolder
import org.bukkit.plugin.Plugin
import java.io.Closeable
import java.util.*

class MenuManager(
    private val taskScheduler: TaskScheduler,
    private val packetManager: PacketManager<*>,
    plugin: Plugin,
) : MenuFactory, MenuRegistry, Closeable {

    // 身份语义（而非 equals）：与 activeMenus 的 `it.value === menu`、getViewers 的 `it === menu` 对齐。
    // 顺带使 route() 的归属判定为 O(1)。
    private val menus: MutableSet<Menu> = Collections.newSetFromMap(IdentityHashMap())
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
                hidePlayerInventory
            ).also(::register)
        }.apply(block)
            .build()

    /**
     * 登记菜单，并通过其事件源跟踪活跃观察者（开/关菜单驱动 [activeMenus]）。
     *
     * `internal` 而非 `private`：真实调用者是 [createChestMenu]；模块内可见使得
     * 测试可用任意 [BukkitMenu] 实现构造归属关系，走的是与生产一致的注册路径。
     * Kotlin 的 `internal` 为模块级，上游调用方不可见。
     */
    internal fun <M : Menu> register(menu: M): M {
        menu.on {
            on<MenuOpenEvent> { activeMenus[player] = menu }
            on<MenuCloseEvent> { if (activeMenus[player] === menu) activeMenus.remove(player) }
            // MONITOR 垫底：记账须在所有 destroy 订阅者之后执行。用 DEFAULT 会因
            // 「register 的订阅早于上游插入 + 稳定排序」而抢先。
            //
            // 别高估它当前的收益：MenuRegistry 对上游暴露的 getActiveMenu/hasActiveMenu/
            // getViewers 只读 activeMenus，而 activeMenus 早在 destroy() 首步 closeAll()
            // 触发 MenuCloseEvent 时就已清空——此刻上游无论记账是否垫底都读不到观察者。
            // MONITOR 实际保住的只有 menus 名册，而它仅经 internal 的 route() 可见。
            // 保留它的理由是原则性的（框架记账最后做）加前瞻性的（若 MenuRegistry 将来
            // 暴露读 menus 的 API，时序已经是对的）。
            on<MenuDestroyEvent>(Priority.MONITOR) {
                menus.remove(menu)
                // 正常路径下这行是 no-op（closeAll 已清空 activeMenus）；它兜的是
                // MenuInteractionListener.onQuit 所防的那种「服务端未先发 InventoryCloseEvent」
                // 的断线错位场景。别当死代码清掉。
                activeMenus.entries.removeIf { it.value === menu }
            }
        }
        return menu.also(menus::add)
    }

    /**
     * 归属裁定：holder 是否为本 manager 名册中的菜单。
     * 同一 server 上可能存在多个 [MenuManager]，各自的 [MenuInteractionListener] 都会收到
     * 全局 Bukkit 事件，故必须裁定归属，否则同一事件被多个 manager 重复处理。
     */
    internal fun route(holder: InventoryHolder?): BukkitMenu? =
        (holder as? BukkitMenu)?.takeIf { it in menus }

    override fun close() {
        // destroy() 会同步派发 MenuDestroyEvent，其 MONITOR 监听会修改 menus 本身；
        // 必须遍历快照，否则会在 forEach 过程中并发结构性修改 menus 导致 CME。
        menus.toList().forEach { it.destroy() }
        HandlerList.unregisterAll(listener)
        menus.clear()
        activeMenus.clear()
    }
}
