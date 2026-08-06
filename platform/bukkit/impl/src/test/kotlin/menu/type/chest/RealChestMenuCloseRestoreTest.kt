package com.github.mayblock.easylib.base.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.item
import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.base.impl.bukkit.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.packetevents.api.PacketManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.*

/** 手动泵：任务入队不执行，pump() 按序执行一轮（观察「同步期不执行、下一 tick 才执行」的时序）。 */
private class ClosePumpScheduler : TaskScheduler {
    private var nextId = 0
    val queue = ArrayDeque<TaskScheduler.Task>()
    override fun scheduleTask(task: TaskScheduler.Task): Int { queue += task; return nextId++ }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
    fun pump() { val round = queue.toList(); queue.clear(); round.forEach { it.onTick(
        mockk<TaskScheduler.TaskScope>(relaxed = true)
    ) } }
}

/**
 * hide 模式下关窗后的背包恢复时序（ESC 关闭 BUG 的回归测试）：
 *
 * InventoryCloseEvent 派发期间，服务端 containerMenu 仍指向正在关闭的容器窗口，此刻
 * updateInventory() 重发的是旧 windowId 的内容包；而 ESC（客户端主动）关窗时客户端早已
 * 本地关窗回到 window 0，旧 windowId 的包会被客户端直接丢弃——被 hide 遮罩写空的背包区
 * 因此永远得不到恢复。正确做法是把恢复动作推迟到下一 tick（此时 containerMenu 已回到
 * window 0，重发包必被客户端接受）。
 *
 * 零侵入观察：不向 RealChestMenu 注入任何测试接缝，玩家用 mockk 代替（菜单在
 * open/close 路径上除 uniqueId 外不读取玩家状态），恢复动作经生产路径
 * [RealChestView.refreshBottom] 落到 Player.updateInventory()，由 mock 捕获验证。
 */
class RealChestMenuCloseRestoreTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun spec(): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java).apply { item(Material.STONE) }.build()

    private fun menu(scheduler: TaskScheduler, hide: Boolean) = RealChestMenu(
        scheduler,
        mockk<PacketManager<*>>(relaxed = true),
        Component.text("t"),
        ChestMenuType.GENERIC_9X3,
        mapOf(0 to spec()),
        hidePlayerInventory = hide,
    )

    private fun player(): Player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns UUID.randomUUID()
    }

    @Test fun `hide 模式：handleClose 不同步恢复背包，下一 tick 恢复恰一次`() {
        val scheduler = ClosePumpScheduler()
        val m = menu(scheduler, hide = true)
        val p = player()
        m.handleOpen(p)
        val queuedBeforeClose = scheduler.queue.size
        m.handleClose(p)
        verify(exactly = 0) { p.updateInventory() } // ESC 关闭时旧 windowId 的同步重发包会被客户端丢弃
        assertEquals(queuedBeforeClose + 1, scheduler.queue.size, "handleClose 应恰好调度 1 个恢复任务")
        scheduler.pump()
        verify(exactly = 1) { p.updateInventory() } // 下一 tick 经 RealChestView.refreshBottom 恢复恰一次
    }

    @Test fun `hide 模式：恢复任务用 Once 触发器（下一 tick 一次性执行）`() {
        val scheduler = ClosePumpScheduler()
        val m = menu(scheduler, hide = true)
        val p = player()
        m.handleOpen(p)
        m.handleClose(p)
        assertEquals(
            listOf<TaskScheduler.Trigger>(TaskScheduler.Trigger.Once),
            scheduler.queue.map { it.trigger },
            "恢复任务应以 Trigger.Once 调度"
        )
    }

    @Test fun `非 hide 模式：handleClose 不调度也不执行任何恢复`() {
        val scheduler = ClosePumpScheduler()
        val m = menu(scheduler, hide = false)
        val p = player()
        m.handleOpen(p)
        m.handleClose(p)
        assertTrue(scheduler.queue.isEmpty(), "未开启 hidePlayerInventory 时不应调度恢复任务")
        scheduler.pump()
        verify(exactly = 0) { p.updateInventory() }
    }

    @Test fun `close+quit 双调只恢复一次（幂等保护同样约束恢复任务）`() {
        val scheduler = ClosePumpScheduler()
        val m = menu(scheduler, hide = true)
        val p = player()
        m.handleOpen(p)
        m.handleClose(p)
        m.handleClose(p) // quit 兜底路径的双调
        assertEquals(1, scheduler.queue.size, "双调场景下只应调度 1 个恢复任务")
        scheduler.pump()
        verify(exactly = 1) { p.updateInventory() }
    }
}
