package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuDestroyEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.listener.MenuInteractionListener
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import com.github.mayblock.easylib.packetevents.PacketManager
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class MenuManagerChestTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun manager() = MenuManager(
        mockk<TaskScheduler>(relaxed = true),
        mockk<PacketManager<*>>(relaxed = true),
        MockBukkit.createMockPlugin()
    )

    @Test fun `createChestMenu 产出真实容器菜单`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3) {
            page(Component.text("t")) {
                slot(0) {
                    item(Material.DIAMOND)
                }
            }
        }
        assertTrue(menu is RealChestMenu)
        assertEquals(Material.DIAMOND, menu.inventory.getItem(0)!!.type)
    }

    @Test fun `声明 onPlace 且 hide=true 构建期报错`() {
        val mgr = manager()
        assertFailsWith<IllegalArgumentException> {
            mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = true) {
                page(Component.text("t")) {
                    slot(0) {
                        onPlace {
                            isCancelled = false
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `关闭事件经监听器清理活跃菜单`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0) { item(Material.DIAMOND) } }
        } as RealChestMenu
        val p = server.addPlayer()
        val view = p.openInventory(menu.inventory)!!
        val listener = MenuInteractionListener(mgr)
        listener.onInvOpen(InventoryOpenEvent(view))
        assertTrue(mgr.hasActiveMenu(p))
        listener.onInvClose(InventoryCloseEvent(view))
        assertFalse(mgr.hasActiveMenu(p))
    }

    // ---- MenuCloseEvent 幂等保护（quit 兜底与正常关窗不重复派发）----
    //
    // 注意：manager() 构造时已把自己的 MenuInteractionListener 注册进 MockBukkit 的
    // PluginManager，因此这里通过 callEvent 触发真实事件派发链，而不是手动调用监听器方法。
    // p.openInventory(...) 在 MockBukkit 中会同步 callEvent(InventoryOpenEvent)，
    // 即 handleOpen 已由 manager 的监听器真实触发。

    @Test
    fun `正常关窗后再触发 quit 兜底，MenuCloseEvent 只派发一次`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0) { item(Material.DIAMOND) } }
        } as RealChestMenu
        var closes = 0
        menu.on { on<MenuCloseEvent> { closes++ } }
        val p = server.addPlayer()
        val view = p.openInventory(menu.inventory)!!

        // 正常关窗：服务端派发 InventoryCloseEvent → onInvClose → handleClose（第 1 次）
        server.pluginManager.callEvent(InventoryCloseEvent(view))
        // 断线兜底：此时玩家的 openInventory 仍指向菜单视图（上面是手工构造的事件，
        // 并未真正关闭视图），模拟「服务端在 quit 前已先触发过 InventoryCloseEvent」
        // 的双调场景 → onQuit → handleClose（第 2 次，应被幂等保护拦下）
        server.pluginManager.callEvent(PlayerQuitEvent(p, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))

        assertEquals(1, closes, "正常关窗 + quit 兜底双调时 MenuCloseEvent 应只派发一次")
    }

    @Test
    fun `直接 quit（无正常关窗），MenuCloseEvent 恰好派发一次`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0) { item(Material.DIAMOND) } }
        } as RealChestMenu
        var closes = 0
        menu.on { on<MenuCloseEvent> { closes++ } }
        val p = server.addPlayer()
        p.openInventory(menu.inventory)!!

        server.pluginManager.callEvent(PlayerQuitEvent(p, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))

        assertEquals(1, closes, "直接 quit 时 MenuCloseEvent 应恰好派发一次")
    }

    // ---- 名册（menus）的可观察面是 route()：destroy 后应被摘除 ----
    //
    // 不能用 hasActiveMenu/getViewers 断言此事：destroy() 第一步 view.closeAll() 即触发
    // InventoryCloseEvent → handleClose → MenuCloseEvent → activeMenus 清空，
    // 那条断言无论记账跑没跑都成立，是空断言。

    @Test
    fun `destroy 后菜单从名册摘除，不再被路由`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0) { item(Material.DIAMOND) } }
        } as RealChestMenu

        assertNotNull(mgr.route(menu), "创建后应在名册中")
        menu.destroy()
        assertNull(mgr.route(menu), "destroy 后应已摘除，否则 menus 只增不减")
    }

    @Test
    fun `destroy 派发 MenuDestroyEvent 给上游订阅者`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0) { item(Material.DIAMOND) } }
        } as RealChestMenu
        var destroys = 0
        menu.on { on<MenuDestroyEvent> { destroys++ } }

        menu.destroy()
        // 二次 destroy 不应让订阅者再收到一次。注意本断言钉的是「对外只派发一次」这个契约，
        // 而非 destroyed 短路这一具体实现：即便删掉 `if (destroyed) return`，第二次 publish
        // 也会落进已被首次 close() 清空的总线，计数仍为 1。要钉短路本身需另找可观察副作用。
        menu.destroy()

        assertEquals(1, destroys, "MenuDestroyEvent 应恰好派发一次")
    }

    @Test
    fun `manager 的记账在上游 destroy 处理器之后执行（名册仍完整）`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0) { item(Material.DIAMOND) } }
        } as RealChestMenu
        var inRegistryDuringHandler: Boolean? = null
        // 上游用默认优先级订阅。register() 的订阅发生在 createChestMenu 返回之前，
        // 故本监听必然晚于 manager 的监听插入；若 manager 用 Priority.DEFAULT 记账，
        // 稳定排序会让 manager 先跑，此处将读到 null。
        // 注意 route() 是 internal：本测试钉住的时序对上游并不可观察（见 MenuManager.register
        // 的 MONITOR 注释）。它守的是「框架记账最后做」这条内部契约本身。
        menu.on { on<MenuDestroyEvent> { inRegistryDuringHandler = mgr.route(menu) != null } }

        menu.destroy()

        assertEquals(true, inRegistryDuringHandler, "上游 destroy 处理器执行时菜单应仍在名册中：manager 记账须以 Priority.MONITOR 垫底")
        assertNull(mgr.route(menu), "记账最终仍须完成")
    }
}
