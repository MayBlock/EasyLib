# 菜单槽位虚拟显示层实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 onUpdate 从「写回真实容器」改为「按 (viewer, slot) 的数据包虚拟显示」：`SlotUpdateEvent` 增加 `player`，`displayItem` 的修改纯视觉、不动真实物品。

**Architecture:** 方案 A（spec §4）：出站改写为唯一显示通道（`RealChestView.attachDisplayMask`，与 `attachHideMask` 同模式），主动刷新走 `player.updateInventory()`（stateId 天然正确）。显示缓存 `SlotDisplayMap` 主线程写/netty 只读；`SlotUpdateLoop` 重写为 per-viewer 计算 + 脏集合并刷新；`RealChestMenu` 装配并在四条数据流触点接线。

**Tech Stack:** Kotlin / Bukkit 26.1.2+（协议 775+，不留旧版兼容分支）/ PacketEvents 2.x / MockBukkit + MockK + JUnit。

**Spec:** `docs/superpowers/specs/2026-07-21-menu-slot-virtual-display-design.md`（已批准；注意维护者已把事件属性 `item` 更名为 `displayItem`，本计划一律用新名）。

## Global Constraints

- 构建命令必须带 JDK 25：`JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew ...`（本机默认 JVM 8 跑不动 Gradle）。
- 测试命令：`JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --console=plain`。
- 每个测试类必须有 MockBukkit 生命周期（`@BeforeTest MockBukkit.mock()` / `@AfterTest MockBukkit.unmock()`）——26.x API 无服务器构造 `ItemStack` 会毒化同 fork 静态注册表（既有教训）。
- `SlotSpec` 保持零运行态契约；运行态（Delay 已触发标记、脏集）只进 `SlotUpdateLoop`。
- 事件属性名一律 `displayItem`（维护者定名）；`SlotUpdateEvent` 构造参数顺序 `(menu, index, player, displayItem)` 对齐 `SlotClickEvent`。
- 不新增依赖；PE 转换用既有 `com.github.mayblock.easylib.impl.bukkit.util.fromBukkit`（`PacketEventsExt.kt`）。
- PE 转换必须懒执行（`by lazy`）：`SpigotConversionUtil` 在 MockBukkit 单测里不可用，生产首读在 netty 线程（`LiveSlot.packetItemCache` 同精度先例）。
- 提交信息中文、conventional commits、尾行 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

---

### Task 1: SlotDisplayMap（显示缓存）

**Files:**
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotDisplayMap.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotDisplayMapTest.kt`

**Interfaces:**
- Consumes: `util.fromBukkit()`（既有）。
- Produces（后续任务依赖的精确签名）：
  - `internal class SlotDisplayMap`
  - `fun commit(viewerId: UUID, slot: Int, base: ItemStack, displayItem: ItemStack): Boolean` —— 结果与基底相同则清条目（透传真实），否则存入；返回视图是否变化（脏）。
  - `fun lookup(viewerId: UUID, slot: Int): Entry?`；`Entry.packetItem: com.github.retrooper.packetevents.protocol.item.ItemStack`（懒转换）、`Entry.bukkitItem: org.bukkit.inventory.ItemStack`。
  - `fun invalidate(slot: Int)`（全 viewer 清该槽）、`fun remove(viewerId: UUID)`、`fun clear()`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.impl.bukkit.util.stack
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.*

class SlotDisplayMapTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private val a: UUID = UUID.randomUUID()
    private val b: UUID = UUID.randomUUID()

    @Test fun `commit 存入假显示并返回脏，重复提交同值不脏`() {
        val map = SlotDisplayMap()
        val base = stack(Material.EMERALD, 3)
        val display = stack(Material.EMERALD, 3) { setDisplayName("§a已存入") }
        assertTrue(map.commit(a, 4, base, display))
        assertEquals(display, map.lookup(a, 4)!!.bukkitItem)
        assertFalse(map.commit(a, 4, base, display.clone())) // 同值 → 不脏
    }

    @Test fun `commit 结果与基底相同则清除条目（透传真实）`() {
        val map = SlotDisplayMap()
        val base = stack(Material.EMERALD, 3)
        map.commit(a, 4, base, stack(Material.EMERALD, 3) { setDisplayName("x") })
        // 规则本轮无修改：display == base → 条目清除，且因视图从假变真而脏
        assertTrue(map.commit(a, 4, base, base.clone()))
        assertNull(map.lookup(a, 4))
        // 再提交一次同基底 → 无条目无变化 → 不脏
        assertFalse(map.commit(a, 4, base, base.clone()))
    }

    @Test fun `双 viewer 缓存隔离`() {
        val map = SlotDisplayMap()
        val base = stack(Material.PAPER)
        map.commit(a, 0, base, stack(Material.PAPER) { setDisplayName("A 的") })
        map.commit(b, 0, base, stack(Material.PAPER) { setDisplayName("B 的") })
        assertNotEquals(map.lookup(a, 0)!!.bukkitItem, map.lookup(b, 0)!!.bukkitItem)
    }

    @Test fun `invalidate 清全 viewer 的该槽，remove 清整个 viewer`() {
        val map = SlotDisplayMap()
        val base = stack(Material.PAPER)
        val d = stack(Material.PAPER) { setDisplayName("x") }
        map.commit(a, 0, base, d); map.commit(b, 0, base, d); map.commit(a, 1, base, d)
        map.invalidate(0)
        assertNull(map.lookup(a, 0)); assertNull(map.lookup(b, 0))
        assertNotNull(map.lookup(a, 1))
        map.remove(a)
        assertNull(map.lookup(a, 1))
    }

    @Test fun `存入为副本，事后改动外部对象不波及缓存`() {
        val map = SlotDisplayMap()
        val display = stack(Material.PAPER, 1) { setDisplayName("x") }
        map.commit(a, 0, stack(Material.PAPER), display)
        display.amount = 64
        assertEquals(1, map.lookup(a, 0)!!.bukkitItem.amount)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotDisplayMapTest" --console=plain`
Expected: FAIL（`SlotDisplayMap` 未定义，编译错误）

- [ ] **Step 3: 最小实现**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 显示层缓存：按 (viewer, slot) 存假显示物品（spec §7）。主线程写（计算/提交），
 * netty 线程只读（出站改写查表），故用并发容器；条目不可变、整体替换。
 *
 * 条目缺失 ⇒ 改写层透传真实物品。[commit] 在结果与真实基底相同时主动清条目，
 * 保证「规则不改 ⇒ 显示真实」与「假→真也要重绘」两个语义。
 */
internal class SlotDisplayMap {

    /** 不可变条目：bukkit 侧用于变更比较；packet 侧懒转换（生产首读在 netty，单测不触碰——LiveSlot 同精度先例）。 */
    internal class Entry(displayItem: ItemStack) {
        val bukkitItem: ItemStack = displayItem.clone()
        val packetItem: com.github.retrooper.packetevents.protocol.item.ItemStack by lazy { bukkitItem.fromBukkit() }
    }

    private val byViewer = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, Entry>>()

    /** 提交一次计算结果；返回该 viewer 对该槽的可见内容是否发生变化（脏 ⇒ 需要重绘）。 */
    fun commit(viewerId: UUID, slot: Int, base: ItemStack, displayItem: ItemStack): Boolean {
        val slots = byViewer.computeIfAbsent(viewerId) { ConcurrentHashMap() }
        val prev = slots[slot]
        return when {
            displayItem == base -> slots.remove(slot) != null // 与真实一致：清条目；有过假显示则脏
            prev?.bukkitItem == displayItem -> false
            else -> { slots[slot] = Entry(displayItem); true }
        }
    }

    fun lookup(viewerId: UUID, slot: Int): Entry? = byViewer[viewerId]?.get(slot)

    /** 真实物品经原生点击变更后（新值未知）：清全 viewer 该槽，改写层透传真实，等下一次重算。 */
    fun invalidate(slot: Int) = byViewer.values.forEach { it.remove(slot) }

    fun remove(viewerId: UUID) { byViewer.remove(viewerId) }

    fun clear() = byViewer.clear()
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令。Expected: PASS（5 tests）

- [ ] **Step 5: 提交**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotDisplayMap.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotDisplayMapTest.kt
git commit -m "feat(menu): SlotDisplayMap 显示层缓存（per-viewer 假物品，netty 只读）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: SlotUpdateEvent(player) + SlotUpdateLoop 重写（per-viewer 显示计算）

**Files:**
- Modify: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/slot/event/SlotEvent.kt`（SlotUpdateEvent 加 player + KDoc）
- Modify: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/slot/dsl/SlotScope.kt`（onUpdate KDoc）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotUpdateLoop.kt`（整体重写）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt`（仅构造参数适配，四触点留给 Task 4）
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuUpdateTest.kt`（按新契约重写）

**Interfaces:**
- Consumes: Task 1 的 `SlotDisplayMap`（`commit/invalidate/remove/clear`）；`ViewerRegistry.snapshot(): List<Player>`；`TaskScheduler.scheduleTask(trigger, block)`（Once=下一 tick 一次、Interval=下一 tick 起每周期——`BukkitTaskScheduler` 用 `runTaskTimer(plugin, r, 0, period)`）。
- Produces:
  - api：`SlotUpdateEvent(menu: Menu, index: Int, player: Player, var displayItem: ItemStack)`。
  - impl：`SlotUpdateLoop(taskScheduler, specs, menu, viewers: () -> List<Player>, display: SlotDisplayMap, repaint: (Player) -> Unit)`，方法 `start()`、`stop()`、`seed(player: Player)`、`recomputeSlot(index: Int)`、`invalidateSlot(index: Int)`。

- [ ] **Step 1: api 变更（编译红即本步的"失败测试"——SlotUpdateLoop 构造点缺 player 必然编不过）**

`SlotEvent.kt` 中 `SlotUpdateEvent` 替换为：

```kotlin
/**
 * 槽位显示更新事件（**显示层契约**，spec 2026-07-21）：
 *
 * [displayItem] 初值 = 容器真实物品的克隆；对它的修改是**纯视觉**的——只影响 [player]
 * 看到的样子（经数据包改写呈现），**不改动真实容器物品**。取出放行时玩家拿到的是真实物品；
 * 放入放行后下一轮以新的真实物品为基底重新计算。
 *
 * 每次触发都从真实基底重算（`displayItem.amount += 1` 不会跨周期累积，需自存状态）。
 * 同槽**同 trigger** 的多规则合并串行（priority 升序，后者可见前者修改）；同槽**不同 trigger**
 * 的规则组各自从真实基底全量重算、后触发者整体覆盖——一个槽的显示规则应共用一个 trigger。
 * 需要真实变更请显式调用 `menu.setItem`。
 *
 * 注意：伪造 `amount` 建议只用于点击即拒的纯展示槽——允许搬运的槽上，客户端会按可见数量
 * 做本地预测（shift/双击聚堆等），与服务器按真实数量的纠正产生可收敛的视觉抖动。
 */
open class SlotUpdateEvent(
    final override val menu: Menu,
    final override val index: Int,
    val player: Player,
    var displayItem: ItemStack
): SlotEvent
```

`SlotScope.kt` 中 `onUpdate` 的 KDoc 替换为：

```kotlin
    /**
     * 显示更新规则（纯视觉，契约见 [SlotUpdateEvent]）：按 [trigger] 周期对每个观察者各触发一次，
     * 对 `displayItem` 的修改只影响该玩家看到的样子，不写回真实容器。
     * 同一槽位上 [trigger] 相等的规则合并为一个调度任务，按 [priority] 升序（小值先）串行执行。
     */
```

- [ ] **Step 2: 确认编译失败**

Run: `JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileKotlin --console=plain`
Expected: FAIL —— `SlotUpdateLoop.kt` 构造 `SlotUpdateEvent(menu, index, current.clone())` 缺参。

- [ ] **Step 3: 重写 SlotUpdateLoop（完整替换文件内容）**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.slot.event.SlotUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.BukkitMenu
import com.github.mayblock.easylib.impl.bukkit.util.stack
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 菜单槽显示更新调度（spec §7/§8）：对每个观察者按 (viewer, slot) 计算假显示并提交
 * [SlotDisplayMap]；**不写回真实容器**。同一槽位上 trigger 相等（值语义）的 [UpdateRule]
 * 合并为一个任务、按 priority 升序串行；不同 trigger 各自排程、各自从真实基底重算，
 * 后触发者整体覆盖（种子按声明序，last-wins 一致）。
 *
 * 脏 viewer 经每 tick 至多一次的合并刷新任务调 [repaint]（生产 = `Player.updateInventory()`，
 * 服务器按正确 stateId 自发全量包、经出站改写层变假——方案 A 的刷新通道）。
 *
 * **按需运行语义**：首个观察者 [start]、最后一个离开 [stop]（与 overlay 侧同语义）。
 * Delay 组"本周期已触发"等运行态在本类（[SlotSpec] 保持零运行态契约）。
 */
internal class SlotUpdateLoop(
    private val taskScheduler: TaskScheduler,
    specs: Map<Int, SlotSpec>,
    private val menu: BukkitMenu,
    private val viewers: () -> List<Player>,
    private val display: SlotDisplayMap,
    private val repaint: (Player) -> Unit,
) {

    private data class GroupKey(val index: Int, val trigger: TaskScheduler.Trigger)

    /** 声明序稳定（groupBy 保序）：种子/重算按此序执行，last-wins 与运行期一致。 */
    private val groupsBySlot: Map<Int, List<Pair<TaskScheduler.Trigger, List<UpdateRule>>>> =
        specs.filterValues { it.updateRules.isNotEmpty() }.mapValues { (_, spec) ->
            spec.updateRules.groupBy { it.trigger }
                .map { (trigger, rules) -> trigger to rules.sortedBy { it.priority } }
        }

    private val taskIds = mutableListOf<Int>()
    private val fired = mutableSetOf<GroupKey>()      // 本激活周期已触发的组（主线程）
    private val dirty = mutableSetOf<UUID>()          // 待重绘 viewer（主线程）
    private var flushScheduled = false

    /** 幂等：已在运行时重复调用直接返回。每次激活重置 Delay 触发标记。 */
    fun start() {
        if (taskIds.isNotEmpty()) return
        fired.clear()
        groupsBySlot.forEach { (index, groups) ->
            groups.forEach { (trigger, ordered) ->
                taskIds += taskScheduler.scheduleTask(trigger) { // 主线程（默认执行器）
                    fired += GroupKey(index, trigger)
                    viewers().forEach { player ->
                        if (compute(index, ordered, player)) markDirty(player.uniqueId)
                    }
                }
            }
        }
    }

    fun stop() {
        taskIds.forEach(taskScheduler::cancelTask)
        taskIds.clear()
        fired.clear()
        dirty.clear()
        display.clear()
    }

    /**
     * 开窗种子（spec §8）：Once/Interval 组必跑；Delay 组仅当本激活周期已触发过
     * （迟到观察者补齐已生效显示，未到期的尊重延迟语义）。**不标脏**——首帧由
     * 开窗自然发包经改写层呈现，无需额外重绘。
     */
    fun seed(player: Player) {
        groupsBySlot.forEach { (index, groups) ->
            groups.forEach { (trigger, ordered) ->
                if (shouldRun(index, trigger)) compute(index, ordered, player)
            }
        }
    }

    /** 真实变更·已知新值路径（setItem / shift 写入后）：同步重算该槽全 viewer 并标脏。 */
    fun recomputeSlot(index: Int) {
        val groups = groupsBySlot[index] ?: return
        viewers().forEach { player ->
            var changed = false
            groups.forEach { (trigger, ordered) ->
                if (shouldRun(index, trigger) && compute(index, ordered, player)) changed = true
            }
            if (changed) markDirty(player.uniqueId)
        }
    }

    /**
     * 真实变更·原生点击放行路径（新值要等 NMS 应用）：立即清条目（改写层透传真实，
     * 与服务器即将广播的内容一致），下一 tick 重算恢复美化（≤1 tick 素颜间隙，spec §8/§10）。
     */
    fun invalidateSlot(index: Int) {
        if (index !in groupsBySlot) return
        display.invalidate(index)
        taskScheduler.scheduleTask(TaskScheduler.Trigger.Once) { recomputeSlot(index) }
    }

    private fun shouldRun(index: Int, trigger: TaskScheduler.Trigger): Boolean =
        trigger !is TaskScheduler.Trigger.Delay || GroupKey(index, trigger) in fired

    /** 跑一组规则并提交；返回该 viewer 视图是否变化。 */
    private fun compute(index: Int, ordered: List<UpdateRule>, player: Player): Boolean {
        val base = menu.getItem(index) ?: stack(Material.AIR)
        val event = SlotUpdateEvent(menu, index, player, base.clone())
        ordered.forEach { rule -> rule.block(event) }
        return display.commit(player.uniqueId, index, base, event.displayItem)
    }

    private fun markDirty(viewerId: UUID) {
        dirty += viewerId
        if (flushScheduled) return
        flushScheduled = true
        taskScheduler.scheduleTask(TaskScheduler.Trigger.Once) { flush() } // 同 tick 多槽/多组合并
    }

    private fun flush() {
        flushScheduled = false
        if (dirty.isEmpty()) return
        val ids = dirty.toSet()
        dirty.clear()
        viewers().forEach { if (it.uniqueId in ids) repaint(it) }
    }
}
```

- [ ] **Step 4: RealChestMenu 构造点适配 + 开窗种子接线（其余触点在 Task 4）**

`RealChestMenu.kt` 字段区改为：

```kotlin
    private val view = RealChestView(this, type, title, packetManager)
    private val viewers = ViewerRegistry()
    internal val displayMap = SlotDisplayMap() // internal：供同模块测试观察显示缓存
    private val updateLoop = SlotUpdateLoop(
        taskScheduler, specs, this, viewers::snapshot, displayMap
    ) { it.updateInventory() }
```

`handleOpen` 改为（start 在 seed 之前：start 清零 Delay 标记开启新激活周期，seed 按当期标记决定 Delay 组是否补跑；Interval 任务首次触发在下一 tick，晚于同步种子）：

```kotlin
    override fun handleOpen(player: Player) {
        if (!viewers.add(player)) return
        updateLoop.start() // 幂等
        updateLoop.seed(player) // 同步种子：InventoryOpenEvent 先于首包，首帧即假显示（spec §8）
        dispatcher.publish(MenuOpenEvent(this, player))
    }
```

并加 import `com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotDisplayMap`。

- [ ] **Step 5: 重写 RealChestMenuUpdateTest（完整替换文件内容）**

保留文件头两个假调度器，新增可手动泵的 `PumpScheduler`；旧「写入真实容器」断言全部按新契约改写：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.scheduler.BukkitAsyncExecutor
import com.github.mayblock.easylib.packetevents.PacketManager
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** 立即同步执行每个被排的任务一次的假调度器（断言 isAsync=false）。 */
private class AsyncTrackingScheduler(
    val asyncFlags: MutableList<Boolean> = mutableListOf()
) : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int {
        asyncFlags += task.executor is BukkitAsyncExecutor; task.onTick(); return 0
    }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

/** 立即同步执行每个被排任务一次，同时记录调度/取消次数，供按需启停断言使用。 */
private class RecordingScheduler : TaskScheduler {
    private var nextId = 0
    val scheduledIds = mutableListOf<Int>()
    val cancelledIds = mutableListOf<Int>()
    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = nextId++; scheduledIds += id; task.onTick(); return id
    }
    override fun cancelTask(taskId: Int): Boolean { cancelledIds += taskId; return true }
    override fun cancelAllTasks() {}
}

/** 手动泵：任务入队不执行，pump() 按序执行一轮（可观察 invalidate 与重算之间的中间态）。 */
private class PumpScheduler : TaskScheduler {
    private var nextId = 0
    val queue = ArrayDeque<TaskScheduler.Task>()
    override fun scheduleTask(task: TaskScheduler.Task): Int { queue += task; return nextId++ }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
    fun pump() { val round = queue.toList(); queue.clear(); round.forEach { it.onTick() } }
}

class RealChestMenuUpdateTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock

    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun menu(scheduler: TaskScheduler, specs: Map<Int, SlotSpec>) =
        RealChestMenu(scheduler, mockk<PacketManager<*>>(relaxed = true), Component.text("t"), ChestMenuType.GENERIC_9X3, specs, hidePlayerInventory = false)

    private fun spec(block: SlotBuilder<InventoryClickEvent>.() -> Unit): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java).apply(block).build()

    @Test fun `更新规则在主线程 tick，写显示缓存而非真实容器`() {
        val scheduler = AsyncTrackingScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem = ItemStack(Material.CLOCK, 5)
            }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)
        // 真实容器不被写回（写回已删的回归断言）
        assertEquals(Material.PAPER, m.inventory.getItem(4)!!.type)
        // 显示缓存有该 viewer 的假物品
        assertEquals(Material.CLOCK, m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.type)
        assertEquals(listOf(false), scheduler.asyncFlags.take(1)) // 更新任务在主线程
    }

    @Test fun `事件携带正确 player，双 viewer 显示独立`() {
        val scheduler = AsyncTrackingScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem = ItemStack(Material.PAPER).also { it.itemMeta = it.itemMeta?.apply { setDisplayName(player.name) } }
            }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p1 = server.addPlayer(); val p2 = server.addPlayer()
        m.handleOpen(p1); m.handleOpen(p2)
        val d1 = m.displayMap.lookup(p1.uniqueId, 4)!!.bukkitItem
        val d2 = m.displayMap.lookup(p2.uniqueId, 4)!!.bukkitItem
        assertEquals(p1.name, d1.itemMeta!!.displayName)
        assertEquals(p2.name, d2.itemMeta!!.displayName)
        assertNotEquals(d1, d2)
    }

    @Test fun `真实基底重算：amount 递增不跨周期累积`() {
        val scheduler = PumpScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) { displayItem.amount += 1 }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)          // 种子计算一次：base=1 → display=2
        scheduler.pump()         // Interval 任务再计算一次：仍从真实基底 1 出发 → display=2（不累积）
        assertEquals(2, m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.amount)
        assertEquals(1, m.inventory.getItem(4)!!.amount) // 真实层不动
    }

    @Test fun `同槽同 trigger 规则合并，按 priority 串行`() {
        val scheduler = AsyncTrackingScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(20)) {
                displayItem.amount += 1 // 低优先级后执行：在高优先级结果上累加
            }
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds), priority = Priority(1)) {
                displayItem = ItemStack(Material.CLOCK, 1) // 高优先级（小值）先执行
            }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)
        val d = m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem
        assertEquals(Material.CLOCK, d.type)
        assertEquals(2, d.amount)
    }

    @Test fun `规则无修改则不留条目（透传真实）`() {
        val scheduler = AsyncTrackingScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) { /* 观察但不改 */ }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)
        assertNull(m.displayMap.lookup(p.uniqueId, 4))
    }

    @Test fun `脏 viewer 合并重绘：同 tick 两槽变更只排一个 flush 任务`() {
        val scheduler = PumpScheduler()
        // 计数器让每次计算结果都不同 ⇒ 每次 Interval 触发都判脏（种子结果≠首个 tick 结果）
        var n1 = 0; var n2 = 0
        val s1 = spec { item(Material.PAPER); onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) { displayItem = ItemStack(Material.CLOCK, ++n1) } }
        val s2 = spec { item(Material.PAPER); onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) { displayItem = ItemStack(Material.DIAMOND, ++n2) } }
        val m = menu(scheduler, mapOf(0 to s1, 1 to s2))
        val p = server.addPlayer()
        m.handleOpen(p)                       // 种子不标脏 → 队列里只有 2 个 Interval 任务
        assertEquals(2, scheduler.queue.size)
        scheduler.pump()                      // 两槽各判脏 → 各 markDirty → 只排 1 个 flush 任务
        assertEquals(1, scheduler.queue.size) // 合并证据：不是 2 个 flush（重绘副作用为 PlayerMock.updateInventory，空实现）
        scheduler.pump()                      // flush 执行，队列清空
        assertEquals(0, scheduler.queue.size)
    }

    @Test fun `不同 trigger 组各自从真实基底重算，后触发者整体覆盖`() {
        val scheduler = PumpScheduler()
        val s = spec {
            item(Material.PAPER)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem = ItemStack(Material.CLOCK, 9)
            }
            onUpdate(trigger = TaskScheduler.Trigger.Interval(2.seconds)) {
                // 独立组：看不到 1s 组的结果，displayItem 是真实基底（PAPER）的克隆
                assertEquals(Material.PAPER, displayItem.type)
                displayItem = ItemStack(Material.DIAMOND, 1)
            }
        }
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p) // 种子按声明序跑两组 → last-wins = DIAMOND
        assertEquals(Material.DIAMOND, m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.type)
    }

    @Test fun `update loop 按观察者存在与否启停，幂等且可重启`() {
        val scheduler = RecordingScheduler()
        val s = spec { onUpdate(trigger = TaskScheduler.Trigger.Once) { } }
        val m = menu(scheduler, mapOf(0 to s))
        assertEquals(0, scheduler.scheduledIds.size)
        val p1 = server.addPlayer()
        m.handleOpen(p1)
        assertEquals(1, scheduler.scheduledIds.size)
        val p2 = server.addPlayer()
        m.handleOpen(p2)
        assertEquals(1, scheduler.scheduledIds.size)
        m.handleClose(p1)
        assertEquals(0, scheduler.cancelledIds.size)
        m.handleClose(p2)
        assertEquals(1, scheduler.cancelledIds.size)
        m.handleOpen(p1)
        assertEquals(2, scheduler.scheduledIds.size)
        m.destroy()
        assertEquals(2, scheduler.cancelledIds.size)
    }
}
```

注：`m.displayMap` 需要 `RealChestMenu.displayMap` 为 `internal val`（Step 4 中按此声明——把 `private val displayMap` 写成 `internal val displayMap`，注释 `// internal 供同模块测试观察显示缓存`）。

- [ ] **Step 6: 跑测试**

Run: `JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenuUpdateTest" --console=plain`
Expected: PASS（种子接线已在 Step 4 的 `handleOpen` 中完成）。

- [ ] **Step 7: 全量测试 + 提交**

Run: `JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --console=plain`
Expected: PASS（其余测试不依赖 onUpdate 写回；若有测试引用 `SlotUpdateEvent` 旧构造器需同步加 player 参数——用 `server.addPlayer()` 或 `mockk<Player>()`）。

```bash
git add -u
git commit -m "feat(menu)!: SlotUpdateEvent 增加 player；SlotUpdateLoop 改为 per-viewer 显示计算

onUpdate 不再写回真实容器：结果按 (viewer, slot) 提交 SlotDisplayMap，
脏 viewer 合并重绘（updateInventory 驱动，方案 A）。开窗种子、Delay 组
本周期触发标记、按需启停语义保留。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: RealChestView.attachDisplayMask（出站改写）

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestView.kt`（attachHideMask 之后追加方法）

**Interfaces:**
- Consumes: Task 1 `SlotDisplayMap.Entry.packetItem`（经 Task 4 以 lambda 注入，本任务不 import SlotDisplayMap）。
- Produces: `fun attachDisplayMask(isViewer: (Player) -> Boolean, lookup: (viewerId: UUID, slot: Int) -> com.github.retrooper.packetevents.protocol.item.ItemStack?): Disposable`

- [ ] **Step 1: 实现（追加到 RealChestView，attachHideMask 之后）**

```kotlin
    /**
     * 显示层改写（spec §7，方案 A 的唯一显示通道）：对 viewer 的容器窗口（windowId != 0）
     * WINDOW_ITEMS / SET_SLOT 包，顶部声明槽（< topSize）查 [lookup]——命中换假物品，
     * 未命中透传真实。carriedItem/光标与底部区不碰（底部归 hideMask；光标必须真实——
     * 放行取出时拿到真身正是契约）。与 [attachHideMask] 同模式：改写不自发包，
     * stateId/windowId 原样透传，天然无递归与失步风险。
     */
    fun attachDisplayMask(
        isViewer: (Player) -> Boolean,
        lookup: (viewerId: java.util.UUID, slot: Int) -> com.github.retrooper.packetevents.protocol.item.ItemStack?,
    ): Disposable =
        packetManager.registerListener(object : PacketListener {
            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (!isViewer(player)) return
                when (e.packetType) {
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        if (packet.windowId == 0) return
                        val items = packet.items.toMutableList()
                        var changed = false
                        for (slot in 0 until minOf(topSize, items.size)) {
                            val display = lookup(player.uniqueId, slot) ?: continue
                            items[slot] = display
                            changed = true
                        }
                        if (changed) packet.items = items
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId == 0) return
                        if (packet.slot < topSize) {
                            lookup(player.uniqueId, packet.slot)?.let { packet.item = it }
                        }
                    }
                }
            }
        })
```

- [ ] **Step 2: 编译**

Run: `JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL。（包监听器按仓库先例不做单测——与 attachHideMask 同精度，走 Task 5 冒烟清单；netty 侧只有查表 + 赋值，无用户代码。）

- [ ] **Step 3: 提交**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestView.kt
git commit -m "feat(menu): RealChestView.attachDisplayMask 出站改写顶部槽假显示

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: RealChestMenu 装配与四触点

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuDisplayTest.kt`（新增）

**Interfaces:**
- Consumes: Task 2 `updateLoop.seed/recomputeSlot/invalidateSlot`、Task 3 `view.attachDisplayMask`、Task 1 `displayMap.lookup(...).packetItem` / `remove`。
- Produces: 无新接口（编排收口）。`displayMap` 为 `internal val`（测试观察点，Task 2 已声明）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.event.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.menu.slot.builder.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.util.stack
import com.github.mayblock.easylib.packetevents.PacketManager
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import org.bukkit.event.inventory.InventoryClickEvent as BukkitInventoryClickEvent

/** 手动泵调度器（与 RealChestMenuUpdateTest 同构；本文件独立副本，测试文件间不共享私有类）。 */
private class PumpScheduler : TaskScheduler {
    private var nextId = 0
    val queue = ArrayDeque<TaskScheduler.Task>()
    override fun scheduleTask(task: TaskScheduler.Task): Int { queue += task; return nextId++ }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
    fun pump() { val round = queue.toList(); queue.clear(); round.forEach { it.onTick() } }
}

class RealChestMenuDisplayTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    /** 美化规则：固定假物品 + 标记显示名，便于断言条目存在（不依赖基底类型）。 */
    private fun fancySpec(base: Material, allowTake: Boolean = false): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java).apply {
            item(base)
            if (allowTake) onTake { isCancelled = false }
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem = ItemStack(Material.NETHER_STAR)
                    .also { it.itemMeta = it.itemMeta?.apply { setDisplayName("§b美化") } }
            }
        }.build()

    private fun menu(scheduler: TaskScheduler, specs: Map<Int, SlotSpec>, pm: PacketManager<*> = mockk(relaxed = true)) =
        RealChestMenu(scheduler, pm, Component.text("t"), ChestMenuType.GENERIC_9X3, specs, hidePlayerInventory = false)

    private fun click(view: InventoryView, rawSlot: Int, action: InventoryAction): BukkitInventoryClickEvent =
        BukkitInventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, ClickType.LEFT, action)

    @Test fun `handleOpen 立即种子填充显示缓存（首帧前）`() {
        val scheduler = PumpScheduler()
        val m = menu(scheduler, mapOf(4 to fancySpec(Material.DIAMOND)))
        val p = server.addPlayer()
        m.handleOpen(p) // 种子同步执行，不依赖任务泵
        assertEquals("§b美化", m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.itemMeta!!.displayName)
    }

    @Test fun `handleClose 清理该 viewer 的显示缓存`() {
        val scheduler = PumpScheduler()
        val m = menu(scheduler, mapOf(4 to fancySpec(Material.DIAMOND)))
        val p = server.addPlayer()
        m.handleOpen(p)
        assertNotNull(m.displayMap.lookup(p.uniqueId, 4))
        m.handleClose(p)
        assertNull(m.displayMap.lookup(p.uniqueId, 4))
    }

    @Test fun `setItem 同步重算：条目以新基底重建`() {
        val scheduler = PumpScheduler()
        val s = SlotBuilder(InventoryClickEvent::class.java).apply {
            item(Material.DIAMOND)
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                displayItem.itemMeta = displayItem.itemMeta?.apply { setDisplayName("§b美化") }
            }
        }.build()
        val m = menu(scheduler, mapOf(4 to s))
        val p = server.addPlayer()
        m.handleOpen(p)
        assertEquals(Material.DIAMOND, m.displayMap.lookup(p.uniqueId, 4)!!.bukkitItem.type)
        m.setItem(4, stack(Material.EMERALD, 7)) // 已知新值路径 → 同步重算
        val entry = m.displayMap.lookup(p.uniqueId, 4)!!
        assertEquals(Material.EMERALD, entry.bukkitItem.type)
        assertEquals(7, entry.bukkitItem.amount)
        assertEquals("§b美化", entry.bukkitItem.itemMeta!!.displayName)
    }

    @Test fun `放行取出：条目立即失效（透传真实），下一 tick 重算恢复`() {
        val scheduler = PumpScheduler()
        val m = menu(scheduler, mapOf(5 to fancySpec(Material.DIAMOND, allowTake = true)))
        val p = server.addPlayer()
        val view = p.openInventory(m.inventory)!!
        m.handleOpen(p)
        assertNotNull(m.displayMap.lookup(p.uniqueId, 5))
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertFalse(e.isCancelled)
        assertNull(m.displayMap.lookup(p.uniqueId, 5)) // 立即失效，改写层透传真实
        scheduler.pump() // 下一 tick：重算（MockBukkit 不执行原生移动，容器仍旧值 → 条目恢复）
        assertNotNull(m.displayMap.lookup(p.uniqueId, 5))
    }

    @Test fun `拒绝取出：条目保持（回滚包被改写层接住，零闪烁路径）`() {
        val scheduler = PumpScheduler()
        val m = menu(scheduler, mapOf(5 to fancySpec(Material.DIAMOND, allowTake = false)))
        val p = server.addPlayer()
        val view = p.openInventory(m.inventory)!!
        m.handleOpen(p)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
        assertNotNull(m.displayMap.lookup(p.uniqueId, 5))
    }

    @Test fun `有 update 规则才注册显示改写监听器`() {
        val pmWith = mockk<PacketManager<*>>(relaxed = true)
        menu(PumpScheduler(), mapOf(4 to fancySpec(Material.DIAMOND)), pmWith)
        // hidePlayerInventory=false ⇒ 唯一的 registerListener 来自 displayMask
        verify(exactly = 1) { pmWith.registerListener(any()) }
        val pmWithout = mockk<PacketManager<*>>(relaxed = true)
        val plain = SlotBuilder(InventoryClickEvent::class.java).apply { item(Material.DIAMOND) }.build()
        menu(PumpScheduler(), mapOf(4 to plain), pmWithout)
        verify(exactly = 0) { pmWithout.registerListener(any()) }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenuDisplayTest" --console=plain`
Expected: FAIL（close 清理、setItem 重算、点击失效、按需注册均未接线；种子已在 Task 2 接好应 PASS）

- [ ] **Step 3: RealChestMenu 接线（逐处修改）**

字段区（Task 2 已有 displayMap/updateLoop）追加：

```kotlin
    private var displayMask: Disposable? = null
    private val hasUpdateRules = specs.values.any { it.updateRules.isNotEmpty() }
```

init 块末尾（attachHideMask 行之后）追加：

```kotlin
        // 显示层改写只在存在 onUpdate 规则时注册（无规则的菜单零开销、行为与旧版一致）
        if (hasUpdateRules) {
            displayMask = view.attachDisplayMask(viewers::contains) { viewerId, slot ->
                displayMap.lookup(viewerId, slot)?.packetItem
            }
        }
```

`setItem` 改为：

```kotlin
    override fun setItem(index: Int, item: ItemStack?) {
        view.setItem(index, item)
        updateLoop.recomputeSlot(index) // 已知新值路径：同步重算显示（spec §8）
    }
```

`handleClose` 在 `if (viewers.isEmpty) updateLoop.stop()` 之前加：

```kotlin
        displayMap.remove(player.uniqueId)
```

`destroy` 在 `hideMask?.dispose()` 之后加：

```kotlin
        displayMask?.dispose()
```

`handleClick` 三个放行分支补失效（原生点击路径，新值待 NMS 应用）：

```kotlin
            is SlotDecision.FireTake -> {
                val ev = SlotTakeEvent(
                    this,
                    decision.slot,
                    player,
                    (e.currentItem ?: stack(Material.AIR)).clone()
                )
                dispatcher.publish(ev)
                if (ev.isCancelled) e.isCancelled = true
                else updateLoop.invalidateSlot(decision.slot)
            }
            is SlotDecision.FirePlace -> {
                val ev = SlotPlaceEvent(
                    this,
                    decision.slot,
                    player,
                    (e.cursor ?: stack(Material.AIR)).clone()
                )
                dispatcher.publish(ev)
                if (ev.isCancelled) e.isCancelled = true
                else updateLoop.invalidateSlot(decision.slot)
            }
```

FireSwap 分支末尾：

```kotlin
                dispatcher.publish(place)
                if (place.isCancelled) e.isCancelled = true
                else updateLoop.invalidateSlot(decision.slot)
```

`handleShiftIntoMenu` 中两处 `view.setItem(p.slot, ...)` 后各加一行（或在循环里收集、`if (placedTotal > 0)` 块内统一重算——采用后者，与既有 updateInventory 相邻）：

```kotlin
        val placedSlots = mutableSetOf<Int>()
        // …循环内两个写入分支分别：placedSlots += p.slot
        if (placedTotal > 0) {
            placedSlots.forEach(updateLoop::recomputeSlot) // 已知新值：同步重算，随后的重绘/广播携带新显示
            val remaining = source.amount - placedTotal
            e.currentItem = if (remaining <= 0) null else source.clone().apply { amount = remaining }
            player.updateInventory()
        }
```

`handleDrag` 末尾（循环完成未整体取消时）：

```kotlin
        for (slot in topRaw) {
            val newItem = e.newItems[slot] ?: continue
            val ev = SlotPlaceEvent(this, slot, player, newItem.clone())
            dispatcher.publish(ev)
            if (ev.isCancelled) { e.isCancelled = true; return }
        }
        // 全部放行：拖拽由 Bukkit 在事件返回后应用，走失效路径（spec §8）
        topRaw.forEach { if (e.newItems[it] != null) updateLoop.invalidateSlot(it) }
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令。Expected: PASS（6 tests）

- [ ] **Step 5: 全量测试 + 提交**

Run: `JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --console=plain`
Expected: PASS

```bash
git add -u
git add platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuDisplayTest.kt
git commit -m "feat(menu): RealChestMenu 装配显示层——改写按需注册与四触点接线

开窗种子/关窗清理/setItem 与 shift 同步重算/原生点击放行失效+下一 tick 恢复。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 文档同步 + 全仓验证

**Files:**
- Modify: `docs/menu-architecture-design.md`（§1 与 §8 的 onUpdate 描述与示例）
- Modify: `docs/superpowers/specs/2026-07-21-menu-slot-virtual-display-design.md`（`item` 措辞 → `displayItem`）

**Interfaces:** 无代码接口；产出真机冒烟清单（见 Step 3）。

- [ ] **Step 1: 架构文档更新**

`docs/menu-architecture-design.md`：§1 概览示例的 onUpdate 行注释与 §8 示例中 `onUpdate` 块，按显示层语义更新——`item =` 改 `displayItem =`，并在 §8 示例后追加一段：

```markdown
onUpdate 为**显示层**（2026-07-21 spec）：`displayItem` 的修改经数据包改写呈现给该
观察者，不写回真实容器；每次触发从真实物品基底重算；取出放行拿到真实物品，放入放行后
以新物品为基底重新美化。真实变更用 `menu.setItem`。amount 伪造建议只用于纯展示槽
（允许搬运的槽上客户端预测会有可收敛的视觉抖动）。
```

- [ ] **Step 2: spec 措辞同步**

spec §6/§7/§8/§11 中事件属性 `item` 的表述统一为 `displayItem`（构造器示意同步为 `(menu, index, player, displayItem)`），文首状态行追加「实施于 2026-07-21 计划」。

- [ ] **Step 3: 真机冒烟清单（写入本计划文件末尾的验收区，供部署后逐项勾选）**

- [ ] 开窗首帧即假物品（无真身闪现）——验证 InventoryOpenEvent 先于首包的 NMS 时序假设
- [ ] 时钟槽每秒走字（updateInventory 通道 + 改写层）
- [ ] 拒绝取出零闪烁；放行取出光标/背包为真实物品
- [ ] 放入放行后按新物品重新美化（≤1 tick 素颜可接受）
- [ ] 双客户端同看一菜单：显示互相隔离（按 player.name 的规则各见其名）
- [ ] hidePlayerInventory=true + onUpdate 共存：底部遮罩与顶部假显示互不干扰
- [ ] （如有条件）Geyser 客户端过一遍基本操作

- [ ] **Step 4: 全仓验证 + 提交**

Run: `JAVA_HOME="/d/Program Files/Zulu/zulu-25" ./gradlew check --console=plain`
Expected: BUILD SUCCESSFUL

```bash
git add -u
git commit -m "docs(menu): onUpdate 显示层语义同步（架构文档 + spec 措辞 displayItem）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
