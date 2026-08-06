package com.github.mayblock.easylib.base.impl.bukkit.overlay

import com.github.mayblock.easylib.base.api.EasyLibApi
import com.github.mayblock.easylib.base.api.bukkit.BukkitEasyLibApi
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayDestroyEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayHideEvent
import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.onAction
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.api.bukkit.scheduler.BukkitTaskExecutors
import com.github.mayblock.easylib.base.api.event.on
import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.base.api.util.Disposable
import com.github.mayblock.easylib.base.impl.bukkit.overlay.builder.OverlaySlotBuilder
import com.github.mayblock.easylib.base.impl.bukkit.overlay.slot.OverlaySlotSpec
import com.github.mayblock.easylib.base.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.base.impl.bukkit.overlay.transport.OverlayTransport
import com.github.mayblock.easylib.base.impl.bukkit.util.SlotDisplayMap
import com.github.mayblock.easylib.base.impl.bukkit.util.stack
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * 记录调用、暴露 attach 时收到的 [com.github.mayblock.easylib.base.impl.bukkit.overlay.transport.OverlayTransport.Callbacks] 的假通道，
 * 替代旧继承切分测试里的假子类。
 */
private class FakeTransport(
    // 可选：让 paintAll 在记录调用的同时，拍下显示层此刻对 watchSlot 的可见内容——
    // 用于钉「seed 必须先于 paintAll」这类时序契约（不传则退化为原有的纯记录行为）。
    private val display: SlotDisplayMap? = null,
    private val watchSlot: Int? = null,
) : OverlayTransport {
    val paintAllCalls = mutableListOf<Player>()
    val paintAllSnapshots = mutableListOf<Material?>()
    val paintCalls = mutableListOf<Pair<Player, Int>>()
    val restoreCalls = mutableListOf<Player>()
    var callbacks: OverlayTransport.Callbacks? = null
        private set
    var disposed = false
        private set

    override fun paintAll(player: Player) {
        paintAllCalls += player
        paintAllSnapshots += watchSlot?.let { display?.lookup(player.uniqueId, it)?.bukkitItem?.type }
    }
    override fun paint(player: Player, slot: Int) { paintCalls += player to slot }
    override fun restore(player: Player) { restoreCalls += player }
    override fun attach(callbacks: OverlayTransport.Callbacks): Disposable {
        this.callbacks = callbacks
        return Disposable { disposed = true }
    }
}

/** 立即同步执行每个被排任务一次，同时记录调度/取消次数，供按需启停断言使用。 */
private class RecordingScheduler : TaskScheduler {
    private var nextId = 0
    val scheduledIds = mutableListOf<Int>()
    val cancelledIds = mutableListOf<Int>()

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = nextId++
        scheduledIds += id
        task.onTick(object : TaskScheduler.TaskScope {
            override fun cancel() {
                cancelTask(id)
            }
        })
        return id
    }

    override fun cancelTask(taskId: Int): Boolean {
        cancelledIds += taskId
        return true
    }

    override fun cancelAllTasks() {}
}

/** 只登记不执行：用于把「条目从何而来」限定为 seed / recomputeSlot 而非定时任务。 */
private class NeverRunScheduler : TaskScheduler {
    private var nextId = 0
    override fun scheduleTask(task: TaskScheduler.Task): Int = nextId++
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

class PlayerOverlayImplTest {

    private var previousApi: EasyLibApi? = null

    @BeforeTest
    fun setUp() {
        MockBukkit.mock()
        // TaskExt 的 scheduleSyncTask/scheduleAsyncTask 排程时会读全局单例取 executor；
        // 本类的 RecordingScheduler 直接内联调用 task.onTick(...)，从不咨询 executor，
        // 所以这里只需保证读取不抛异常即可，executor 的具体行为对断言无关。
        // 非 relaxed mock：除 taskExecutors 外的任何触碰都会快速失败，测试不静默依赖全局状态。
        previousApi = try { EasyLibApi.api } catch (_: UninitializedPropertyAccessException) { null }
        EasyLibApi.api = mockk<BukkitEasyLibApi> {
            every { taskExecutors } returns object : BukkitTaskExecutors {
                override val sync: TaskExecutor = TaskExecutor { it() }
                override val async: TaskExecutor = TaskExecutor { it() }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        // 尽量恢复全局单例，避免 mock 泄漏到同 JVM 的后续测试类（lateinit 无法退回未初始化态）。
        previousApi?.let { EasyLibApi.api = it }
        MockBukkit.unmock()
    }

    private fun specOf(item: ItemStack, block: OverlaySlotBuilder.() -> Unit = {}): OverlaySlotSpec =
        OverlaySlotBuilder().apply {
            item(item)
        }.apply(block).build()

    private fun mockPlayer(): Player = mockk<Player>(relaxed = true).also {
        every { it.isOnline } returns true
        every { it.uniqueId } returns UUID.randomUUID() // 显示层按 uuid 索引，必须稳定且唯一
    }

    private fun build(
        specs: Map<Int, OverlaySlotSpec>,
        scheduler: TaskScheduler = RecordingScheduler(),
        transport: FakeTransport = FakeTransport(),
        display: SlotDisplayMap = SlotDisplayMap(),
    ): Triple<PlayerOverlayImpl, FakeTransport, TaskScheduler> {
        val map = SlotMap(specs)
        val overlay = PlayerOverlayImpl(specs, map, display, scheduler, transport)
        return Triple(overlay, transport, scheduler)
    }

    // ---- getItem/setItem：语义照搬原 AbstractPlayerOverlayTest ----

    @Test
    fun `getItem 返回声明槽当前物品，未声明或 AIR 返回 null`() {
        val (o, _, _) = build(mapOf(3 to specOf(stack(Material.STONE, 5)), 4 to specOf(stack(Material.AIR))))
        assertEquals(Material.STONE, o.getItem(3)!!.type)
        assertEquals(5, o.getItem(3)!!.amount)
        assertNull(o.getItem(4))
        assertNull(o.getItem(9))
    }

    @Test
    fun `getItem 返回防御副本，改动返回值不波及 overlay 内部`() {
        val (o, _, _) = build(mapOf(3 to specOf(stack(Material.STONE, 1))))
        o.getItem(3)!!.amount = 99
        assertEquals(1, o.getItem(3)!!.amount)
    }

    @Test
    fun `setItem 存入防御副本，改动入参不波及 overlay 内部`() {
        val (o, _, _) = build(mapOf(3 to specOf(stack(Material.AIR))))
        val input = stack(Material.DIAMOND, 1)
        o.setItem(3, input)
        input.amount = 99
        assertEquals(1, o.getItem(3)!!.amount)
    }

    @Test
    fun `setItem 写入声明槽，null 等价 AIR，并对在线观察者重绘`() {
        val (o, transport, _) = build(mapOf(3 to specOf(stack(Material.AIR))))
        val player = mockPlayer()
        o.show(player)
        transport.paintCalls.clear() // 只关心 setItem 触发的重绘

        o.setItem(3, stack(Material.DIAMOND, 2))
        assertEquals(Material.DIAMOND, o.getItem(3)!!.type)
        o.setItem(3, null)
        assertNull(o.getItem(3))
        assertEquals(listOf(player to 3, player to 3), transport.paintCalls)
    }

    @Test
    fun `setItem 对未声明槽抛 IllegalArgumentException`() {
        val (o, _, _) = build(mapOf(3 to specOf(stack(Material.AIR))))
        assertFailsWith<IllegalArgumentException> { o.setItem(4, stack(Material.DIAMOND)) }
    }

    // ---- show/hide/quit/hideIfViewing：与 transport 的交互次数 ----

    @Test
    fun `show 调用 paintAll 恰好一次并登记观察者`() {
        val (o, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        val player = mockPlayer()
        o.show(player)
        assertEquals(listOf(player), transport.paintAllCalls)
    }

    @Test
    fun `hide 调用 restore 恰好一次`() {
        val (o, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        val player = mockPlayer()
        o.show(player)
        assertTrue(o.hide(player))
        assertEquals(listOf(player), transport.restoreCalls)
    }

    @Test
    fun `hide 对未观察玩家返回 false 且不调用 restore`() {
        val (o, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        val player = mockPlayer()
        assertFalse(o.hide(player))
        assertTrue(transport.restoreCalls.isEmpty())
    }

    @Test
    fun `onPlayerQuit 移除观察者并派发 OverlayHideEvent，不调用 restore，幂等`() {
        val (o, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        val player = mockPlayer()
        o.show(player)
        var hides = 0
        o.on { on<OverlayHideEvent> { hides++ } }
        o.onPlayerQuit(player)
        assertEquals(1, hides)
        assertTrue(transport.restoreCalls.isEmpty()) // 断线：不还原视觉
        o.onPlayerQuit(player) // 已不在观察者集合：不重复派发
        assertEquals(1, hides)
    }

    @Test
    fun `hideIfViewing 对观察中的玩家移除观察者、派发 OverlayHideEvent 并调用 restore`() {
        val (o, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        val player = mockPlayer()
        o.show(player)
        var hides = 0
        o.on { on<OverlayHideEvent> { hides++ } }
        o.hideIfViewing(player)
        assertEquals(1, hides)
        assertEquals(listOf(player), transport.restoreCalls)
    }

    @Test
    fun `hideIfViewing 对非观察者是无操作`() {
        val (o, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        val bystander = mockPlayer()
        var hides = 0
        o.on { on<OverlayHideEvent> { hides++ } }
        o.hideIfViewing(bystander)
        assertEquals(0, hides)
        assertTrue(transport.restoreCalls.isEmpty())
    }

    // ---- destroy ----

    @Test
    fun `destroy 后 isDestroyed 为真`() {
        val (o, _, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        assertFalse(o.isDestroyed)
        o.destroy()
        assertTrue(o.isDestroyed)
    }

    @Test
    fun `destroy 末尾派发 OverlayDestroyEvent，且恰好只派发一次`() {
        val (o, _, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        var calls = 0
        o.on { on<OverlayDestroyEvent> { calls++ } }
        o.destroy()
        // 二次 destroy 不应让订阅者再收到一次。注意本断言钉的是「对外只派发一次」这个契约，
        // 而非 isDestroyed 短路这一具体实现：即便删掉 `if (isDestroyed) return`，第二次 publish
        // 也会落进已被首次 close() 清空的总线，计数仍为 1。
        o.destroy()
        assertEquals(1, calls, "OverlayDestroyEvent 应恰好派发一次")
    }

    @Test
    fun `派发 OverlayDestroyEvent 时 isDestroyed 已置位（订阅者看到一致状态）`() {
        val (o, _, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        var seenDestroyed: Boolean? = null
        o.on { on<OverlayDestroyEvent> { seenDestroyed = overlay.isDestroyed } }
        o.destroy()
        assertTrue(seenDestroyed == true, "派发须晚于 isDestroyed = true；否则订阅者读到不一致状态")
    }

    @Test
    fun `destroy 对每个 viewer 调用 restore 并释放 transportSub`() {
        val (o, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        val p1 = mockPlayer()
        val p2 = mockPlayer()
        o.show(p1)
        o.show(p2)
        o.destroy()
        assertEquals(setOf(p1, p2), transport.restoreCalls.toSet())
        assertTrue(transport.disposed)
    }

    // ---- 事件面：声明的 handler 按 index 过滤 + 点击回调经主线程派发 ----

    @Test
    fun `声明的 onAction 处理器经总线按 index 过滤派发`() {
        var actions = 0
        val (_, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE)) { onAction { actions++ } }))
        val player = mockPlayer()
        transport.callbacks!!.onClick(player, 3, ClickType.LEFT)
        transport.callbacks!!.onClick(player, 4, ClickType.LEFT)
        assertEquals(1, actions)
    }

    @Test
    fun `onAction 块内可用 when 区分 Click 与 Interact 来源`() {
        val kinds = mutableListOf<String>()
        val (_, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE)) {
            onAction {
                kinds += when (this) {
                    is OverlaySlotActionEvent.Click -> "click:$clickType"
                    is OverlaySlotActionEvent.Interact -> "interact:$action"
                }
            }
        }))
        val player = mockPlayer()
        transport.callbacks!!.onClick(player, 3, ClickType.LEFT)
        transport.callbacks!!.onInteract(player, 3, OverlaySlotActionEvent.Interact.Action.RIGHT_CLICK)
        assertEquals(listOf("click:LEFT", "interact:RIGHT_CLICK"), kinds)
    }

    @Test
    fun `callbacks onClick 经调度器转发到主线程后派发 Click 事件`() {
        val (o, transport, _) = build(mapOf(3 to specOf(stack(Material.STONE))))
        val player = mockPlayer()
        var received: OverlaySlotActionEvent.Click? = null
        o.on { on<OverlaySlotActionEvent.Click> { received = this } }
        transport.callbacks!!.onClick(player, 3, ClickType.RIGHT)
        assertEquals(3, received?.index)
        assertEquals(ClickType.RIGHT, received?.clickType)
    }

    // ---- 按需启停（本次需求的验收）----

    @Test
    fun `update loop 按观察者存在与否启停，幂等且可重启`() {
        val scheduler = RecordingScheduler()
        val spec = mapOf(0 to specOf(stack(Material.AIR)) {
            onUpdate(TaskScheduler.Trigger.Once) { }
        })
        val (o, _, _) = build(spec, scheduler = scheduler)

        // ① 构造后（有 update 规则）不调度任何任务
        assertEquals(0, scheduler.scheduledIds.size)

        // ② 首个 show -> 任务被调度
        val p1 = mockPlayer()
        o.show(p1)
        assertEquals(1, scheduler.scheduledIds.size)

        // ③ 第二个 viewer 加入 -> 不重复调度（幂等）
        val p2 = mockPlayer()
        o.show(p2)
        assertEquals(1, scheduler.scheduledIds.size)

        // 还有一个 viewer 在场时 hide 不应停止
        o.hide(p1)
        assertEquals(0, scheduler.cancelledIds.size)

        // ④ 最后一个 hide -> 任务被取消
        o.hide(p2)
        assertEquals(1, scheduler.cancelledIds.size)

        // ⑤ 再次 show -> 重新调度
        o.show(p1)
        assertEquals(2, scheduler.scheduledIds.size)

        // ⑥ destroy -> 取消
        o.destroy()
        assertEquals(2, scheduler.cancelledIds.size)
    }

    // ---- per-viewer 显示层：时序与清理 ----

    @Test
    fun `show 先种子再全量渲染，paintAll 发生时显示层已含该玩家的种子条目`() {
        val display = SlotDisplayMap()
        val spec = specOf(stack(Material.PAPER)) {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { displayItem = stack(Material.CLOCK) }
        }
        // NeverRunScheduler：只排程不执行，确保条目只可能来自 seed 而非定时任务
        // watchSlot=4：FakeTransport 在 paintAll 调用的那一刻拍下显示层快照，而非事后查——
        // 若把 show() 里 seed/paintAll 两行调换顺序，这里会拍到 null 而不是 CLOCK。
        val transport = FakeTransport(display, watchSlot = 4)
        val (o, t, _) = build(mapOf(4 to spec), scheduler = NeverRunScheduler(), transport = transport, display = display)
        val p = mockPlayer()

        o.show(p)

        assertEquals(Material.CLOCK, assertNotNull(display.lookup(p.uniqueId, 4)).bukkitItem.type)
        assertEquals(listOf(p), t.paintAllCalls)
        assertEquals(listOf<Material?>(Material.CLOCK), t.paintAllSnapshots) // paintAll 时刻，显示层已可见种子条目
    }

    @Test
    fun `setItem 无条件重绘全体观察者，即便显示层因规则不改物品而无条目`() {
        val display = SlotDisplayMap()
        // 规则完全不改物品 ⇒ commit 恒返回 false（前后都无显示条目）；基底变更仍必须重绘
        // （菜单侧不存在的坑）。本测试钉的是「重绘不依赖显示层是否有条目」这一条契约。
        val spec = specOf(stack(Material.PAPER, 1)) {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { }
        }
        val (o, t, _) = build(mapOf(4 to spec), scheduler = NeverRunScheduler(), display = display)
        val p = mockPlayer()
        o.show(p)
        t.paintCalls.clear()

        o.setItem(4, stack(Material.DIAMOND, 3))

        assertEquals(listOf(p to 4), t.paintCalls)
        assertEquals(Material.DIAMOND, o.getItem(4)!!.type) // 基底确实变了
        assertNull(display.lookup(p.uniqueId, 4)) // 显示层确无条目：证明重绘并非由「显示层有变化」触发
    }

    @Test
    fun `setItem 重算显示层，显示条目基于新基底重新计算`() {
        val display = SlotDisplayMap()
        // 规则依赖基底（amount+1）：显示条目在 setItem 前后必须跟着新基底变化，
        // 否则会出现「显示层缓存的是旧基底算出的假值，重绘又无条件发生」的鬼影。
        val spec = specOf(stack(Material.PAPER, 1)) {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { displayItem.amount += 1 }
        }
        val (o, _, _) = build(mapOf(4 to spec), scheduler = NeverRunScheduler(), display = display)
        val p = mockPlayer()
        o.show(p)

        val seeded = assertNotNull(display.lookup(p.uniqueId, 4))
        assertEquals(Material.PAPER, seeded.bukkitItem.type)
        assertEquals(2, seeded.bukkitItem.amount)

        o.setItem(4, stack(Material.DIAMOND, 3))

        val recomputed = assertNotNull(display.lookup(p.uniqueId, 4))
        assertEquals(Material.DIAMOND, recomputed.bukkitItem.type)
        assertEquals(4, recomputed.bukkitItem.amount)
    }

    @Test
    fun `hide 清除该玩家的显示条目，不影响其他观察者`() {
        val display = SlotDisplayMap()
        val spec = specOf(stack(Material.PAPER)) {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { displayItem = stack(Material.CLOCK) }
        }
        val (o, _, _) = build(mapOf(4 to spec), scheduler = NeverRunScheduler(), display = display)
        val a = mockPlayer()
        val b = mockPlayer()
        o.show(a)
        o.show(b)

        o.hide(a)

        assertNull(display.lookup(a.uniqueId, 4))
        assertNotNull(display.lookup(b.uniqueId, 4))
    }

    @Test
    fun `玩家断线同样清除其显示条目`() {
        val display = SlotDisplayMap()
        val spec = specOf(stack(Material.PAPER)) {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { displayItem = stack(Material.CLOCK) }
        }
        val (o, _, _) = build(mapOf(4 to spec), scheduler = NeverRunScheduler(), display = display)
        val bystander = mockPlayer()
        val p = mockPlayer()
        // 先让 bystander 在场：若唯一 viewer 是 p，removeViewer 会触发 updateLoop.stop()
        // 的 display.clear() 兜底清理，断言即便 display.remove(playerId) 被误删也照样通过——
        // 本测试要钉的是 per-viewer remove，不是 stop() 的清空。
        o.show(bystander)
        o.show(p)

        o.onPlayerQuit(p)

        assertNull(display.lookup(p.uniqueId, 4))
        assertNotNull(display.lookup(bystander.uniqueId, 4)) // 佐证：清理是按 viewer 定点做的
    }
}
