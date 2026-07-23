# WaitingLobbyFeature / Counter 审计修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以最小代码量修复审计发现的 8 个正确性问题：Counter 的 start/stop 竞态、虚假/失序生命周期事件；WaitingLobbyFeature 的监听未注册、完成/中止语义混淆、人数跌破下限不停、n² 广播、卸载不停、先加人后安装卡死。

**Architecture:** Counter 改为「一把锁 + 单一运行状态」并新增 `stopTarget`/`Completed`（到达目标值自动停止的内聚职责下沉到 Counter）；WaitingLobbyFeature 改为 install 时立即订阅、用 `Completed`/`Stopped` 分流完成与中止、Leave 补人数检查、checker 兜底启动。全部为纯新增或行为修复，**不破坏既有 public API**（构造参数只追加带默认值的尾参）。

**Tech Stack:** Kotlin / JUnit Platform + kotlin.test + MockK / MockBukkit（platform-bukkit-impl）

## Global Constraints

- JVM 工具链 Java 25，构建一律用 `./gradlew`（Windows 下 `.\gradlew.bat`，下文统一写 `./gradlew`）。
- 这是库项目：**不得删除/改签名任何既有 public 符号**。`Counter` 构造函数只允许在**末尾**追加带默认值的参数（保持源码兼容）；`Event.Started.initialValue` 属性名保持不变（语义改为"本次启动时的真实值"，名字仍成立）。
- 依赖方向不变：common-impl 不得引用任何 Bukkit 类型。
- 注释与提交信息风格与仓库一致（中文，`fix(scope): ...`）。
- 每个 commit 末尾附 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

## 设计决策与理由（两个 Task 共同遵循）

1. **锁而非无锁**：start/stop 是低频操作（一局游戏数次），锁开销无关紧要；旧实现用 3 个原子变量拼状态机，产生了 check-then-act 窗口（scheduleTask 与存句柄之间可被 stop 插队 → 任务泄漏 + Counter 永久卡死）。一把锁使「调度任务 + 存句柄」成为不可分割的临界区，正确性一眼可审计。
2. **`isRunning` 从 `disposable != null` 推导**：两份状态必有不同步瞬间（旧 Stopped 处理器读到恒 false 即由此而来），单一事实来源让不一致从根上不存在。
3. **`stopTarget`/`Completed` 下沉进 Counter**：「数到目标值自动停」是倒计时的内聚职责；留在调用方意味着每个上游都要重复 stop/reset 模板，且 `Stopped` 无法区分完成与中止（正是"开赛瞬间发'倒计时终止'"bug 的根因）。`Completed` 与 `Stopped` 互斥（由同一临界区裁决），Feature 侧各接一个事件即可，耦合点最少。
4. **`tryRelease()` 复用**：外部 stop 与自动完成走同一个"原子认领释放权"的私有函数，DRY 且保证两个终态事件恰好只发一个。
5. **事件发出位置**：`Started` 在锁内、调度之前发出，保证 Started 先于首个 Tick（旧代码在调度后发，立即触发型调度器会失序）；`Stopped`/`Completed` 在认领成功后、锁外发出，避免持锁执行任意用户代码。约束（写入 KDoc）：Started 处理器内不得回调本对象 start/stop。
6. **Feature 的启动入口收敛为 `tryStartCountdown()`**：join 事件（即时性）与每秒 checker（兜底：修复"先加人后安装永不启动"）复用同一判断，条件只写一处。
7. **复位策略集中在终态处理器**：`counter.reset()` 只出现在 `Completed`/`Stopped` 两个处理器里，Leave 处理器只负责"决定停"（`counter.stop()`），善后（复位值、复位 HUD、发消息）由终态事件统一承担——事件驱动下的内聚点。
8. **测试注入**：Feature 的 `counter` 构造参数保留并被测试利用（注入可探测的 Counter）；`EasyLibApi.api` 注入 relaxed mock 使 `sendPackets` 成为无害 no-op，测试不依赖 PacketEvents 运行时。

---

### Task 1: Counter——锁化重写 + stopTarget/Completed

**Files:**
- Modify: `common-impl/src/main/kotlin/com/github/mayblock/easylib/impl/util/Counter.kt`（整文件重写）
- Test: `common-impl/src/test/kotlin/com/github/mayblock/easylib/impl/util/CounterTest.kt`（新建）

**Interfaces:**
- Consumes: `TaskScheduler` / `TaskScheduler.Trigger.Interval` / `TaskScheduler.TaskScope`（common-api，不变）、`Disposable`、`SimpleEventBus`。
- Produces（Task 2 依赖的精确签名）:
  - `Counter(interval: Duration, initialValue: Long = 0, step: Long = 1, notifyExecutor: TaskExecutor = TaskExecutor.Direct, eventBus: EventBus<Event> = SimpleEventBus(), stopTarget: Long? = null)`
  - `val isRunning: Boolean`；`fun get(): Long`；`fun set(value: Long)`；`fun reset()`；`fun start(scheduler: TaskScheduler)`；`fun stop()`
  - `Counter.Event.Tick(value: Long)` / `Started(initialValue: Long)` / `Stopped(finalValue: Long)` / `Completed(finalValue: Long)`
  - 事件契约：到达 stopTarget → 自动停 + `Completed`（不发 Stopped）；外部 `stop()` → `Stopped`；未运行时 `stop()` 静默。

- [ ] **Step 1: 写失败测试**

创建 `common-impl/src/test/kotlin/com/github/mayblock/easylib/impl/util/CounterTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.util

import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 手动泵调度器：不关心 Trigger 周期，每次 [tick] 同步触发一轮全部在册任务。
 * 支持任务在 onTick 内经 cancelTask/TaskScope.cancel 取消自身（Counter 自动完成路径依赖这一点）。
 */
private class PumpScheduler : TaskScheduler {
    private val tasks = LinkedHashMap<Int, TaskScheduler.Task>()
    private var nextId = 1
    val activeCount get() = tasks.size

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = nextId++
        tasks[id] = task
        return id
    }

    override fun cancelTask(taskId: Int): Boolean = tasks.remove(taskId) != null
    override fun cancelAllTasks() = tasks.clear()

    fun tick() {
        tasks.entries.toList().forEach { (id, task) ->
            if (id in tasks) task.onTick(object : TaskScheduler.TaskScope {
                override fun cancel() { tasks.remove(id) }
            })
        }
    }
}

class CounterTest {

    private val scheduler = PumpScheduler()

    private fun recordEvents(counter: Counter): MutableList<Counter.Event> {
        val events = mutableListOf<Counter.Event>()
        counter.on {
            on<Counter.Event.Started> { events += this }
            on<Counter.Event.Tick> { events += this }
            on<Counter.Event.Stopped> { events += this }
            on<Counter.Event.Completed> { events += this }
        }
        return events
    }

    @Test
    fun `Started 携带启动时的真实计数值而非构造初值`() {
        val counter = Counter(50.milliseconds, initialValue = 10)
        val events = recordEvents(counter)
        counter.set(3)
        counter.start(scheduler)
        assertEquals(listOf<Counter.Event>(Counter.Event.Started(3)), events)
    }

    @Test
    fun `Tick 按 step 步进`() {
        val counter = Counter(50.milliseconds, initialValue = 3, step = -1)
        val events = recordEvents(counter)
        counter.start(scheduler)
        scheduler.tick()
        scheduler.tick()
        assertEquals(
            listOf<Counter.Event>(
                Counter.Event.Started(3),
                Counter.Event.Tick(2),
                Counter.Event.Tick(1),
            ), events
        )
        assertEquals(1, counter.get())
    }

    @Test
    fun `到达 stopTarget 自动停止并发 Completed 而非 Stopped`() {
        val counter = Counter(50.milliseconds, initialValue = 2, step = -1, stopTarget = 0)
        val events = recordEvents(counter)
        counter.start(scheduler)
        scheduler.tick()
        scheduler.tick()
        assertEquals(
            listOf<Counter.Event>(
                Counter.Event.Started(2),
                Counter.Event.Tick(1),
                Counter.Event.Tick(0),
                Counter.Event.Completed(0),
            ), events
        )
        assertFalse(counter.isRunning)
        assertEquals(0, scheduler.activeCount, "自动完成必须取消调度任务")
        scheduler.tick()
        assertEquals(4, events.size, "完成后不得再有任何事件")
    }

    @Test
    fun `外部 stop 发 Stopped 且取消任务`() {
        val counter = Counter(50.milliseconds, initialValue = 5, step = -1)
        val events = recordEvents(counter)
        counter.start(scheduler)
        scheduler.tick()
        counter.stop()
        assertEquals(Counter.Event.Stopped(4), events.last())
        assertFalse(counter.isRunning)
        assertEquals(0, scheduler.activeCount)
    }

    @Test
    fun `未启动时 stop 静默--不发虚假 Stopped`() {
        val counter = Counter(50.milliseconds)
        val events = recordEvents(counter)
        counter.stop()
        counter.stop()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `重复 start 幂等--只调度一个任务`() {
        val counter = Counter(50.milliseconds)
        val events = recordEvents(counter)
        counter.start(scheduler)
        counter.start(scheduler)
        assertEquals(1, scheduler.activeCount)
        assertEquals(1, events.count { it is Counter.Event.Started })
    }

    @Test
    fun `stop 后可重新 start--完整生命周期可循环`() {
        val counter = Counter(50.milliseconds, initialValue = 5, step = -1)
        val events = recordEvents(counter)
        counter.start(scheduler)
        counter.stop()
        counter.reset()
        counter.start(scheduler)
        scheduler.tick()
        assertTrue(counter.isRunning)
        assertEquals(Counter.Event.Tick(4), events.last())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :common-impl:test --tests "com.github.mayblock.easylib.impl.util.CounterTest"`
Expected: **编译失败**（`Completed`、`stopTarget` 尚不存在）——这就是本轮的红灯。

- [ ] **Step 3: 重写 Counter**

整文件替换 `common-impl/src/main/kotlin/com/github/mayblock/easylib/impl/util/Counter.kt`：

```kotlin
package com.github.mayblock.easylib.impl.util

import com.github.mayblock.easylib.api.event.EventBus
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * 周期计数器：按 [interval] 周期以 [step] 步进，向订阅方发布 [Event]。
 *
 * 事件契约：
 * - [Event.Started]：start() 成功启动时发出，携带启动时的真实计数值；保证先于首个 [Event.Tick]。
 * - [Event.Tick]：每周期步进后发出，携带步进后的值。
 * - [Event.Completed]：计数到达 [stopTarget] 时自动停止并发出（仅当 stopTarget 非 null）；与 Stopped 互斥。
 * - [Event.Stopped]：仅由外部 [stop] 中止时发出；未运行时 stop() 静默、不发事件。
 *
 * 线程安全：start / stop / 自动完成经内部锁互斥，可从任意线程调用。
 * 注意：[Event.Started] 在锁内发出，Started 处理器内不得回调本对象的 start/stop。
 */
class Counter(
    private val interval: Duration,
    private val initialValue: Long = 0,
    private val step: Long = 1,
    private val notifyExecutor: TaskExecutor = TaskExecutor.Direct,
    private val eventBus: EventBus<Event> = SimpleEventBus(),
    private val stopTarget: Long? = null,
) : EventSource<Event> by eventBus {

    private val lock = Any()

    /** 唯一的运行状态：非 null 即运行中。由 [lock] 保护，[isRunning] 从它推导，杜绝双状态失步。 */
    private var disposable: Disposable? = null

    val isRunning: Boolean get() = synchronized(lock) { disposable != null }

    private val counter = AtomicLong(initialValue)

    fun get() = counter.get()
    fun set(value: Long) = counter.set(value)
    fun reset() = counter.set(initialValue)

    fun start(scheduler: TaskScheduler) {
        synchronized(lock) {
            if (disposable != null) return
            // 调度前发 Started：保证 Started ≺ 首个 Tick（立即触发型调度器下调度后发会失序）。
            eventBus.emit(Event.Started(counter.get()))
            val taskId = scheduler.scheduleTask(
                TaskScheduler.Trigger.Interval(interval), notifyExecutor
            ) { onTick() }
            // 与 scheduleTask 同临界区：stop() 不可能再插进"任务已跑、句柄未存"的窗口。
            disposable = Disposable { scheduler.cancelTask(taskId) }
        }
    }

    fun stop() {
        if (tryRelease()) eventBus.emit(Event.Stopped(counter.get()))
    }

    private fun onTick() {
        val value = counter.addAndGet(step)
        eventBus.emit(Event.Tick(value))
        // value == stopTarget：Long 与 Long? 比较，stopTarget 为 null 时恒 false。
        if (value == stopTarget && tryRelease()) {
            eventBus.emit(Event.Completed(value))
        }
    }

    /**
     * 原子地认领并释放运行状态。返回是否由本次调用完成释放——
     * 外部 stop 与自动完成共用此认领，保证 Stopped/Completed 恰好只发一个。
     */
    private fun tryRelease(): Boolean = synchronized(lock) {
        disposable?.also {
            it.dispose()
            disposable = null
        } != null
    }

    sealed interface Event : com.github.mayblock.easylib.api.event.Event {
        data class Tick(val value: Long) : Event
        data class Started(val initialValue: Long) : Event
        data class Stopped(val finalValue: Long) : Event
        data class Completed(val finalValue: Long) : Event
    }
}
```

要点核对（与旧实现的差异即修复项）：删除 `AtomicBoolean _isRunning`、`AtomicReference` 与 placeholder 占坑（竞态根源）；`stop()` 未运行时静默；`Started` 取 `counter.get()` 且先于调度；新增 `stopTarget` 尾参与 `Completed` 事件；删除自导入 `import ...Counter.Event`。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :common-impl:test --tests "com.github.mayblock.easylib.impl.util.CounterTest"`
Expected: 7 个测试全部 PASS。

- [ ] **Step 5: 全模块回归（确认无既有调用被破坏）**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（`WaitingLobbyFeature` 是仓库内唯一 Counter 调用方，其位置参数 `Counter(1.ticks, startCountdown.toTicks(), -1)` 不受尾部追加参数影响）。

- [ ] **Step 6: Commit**

```bash
git add common-impl/src/main/kotlin/com/github/mayblock/easylib/impl/util/Counter.kt common-impl/src/test/kotlin/com/github/mayblock/easylib/impl/util/CounterTest.kt
git commit -m "fix(util): Counter 锁化消除 start/stop 竞态；新增 stopTarget/Completed 事件语义

- start/stop/自动完成经单一锁互斥，删除三原子变量拼接的 check-then-act 窗口（旧实现可致任务泄漏且 Counter 永久卡死）
- isRunning 从唯一运行状态推导，杜绝双状态失步
- stop() 未运行时静默；Started 携带真实当前值并保证先于首个 Tick
- 新增 stopTarget（尾参、默认 null，源码兼容）：到达目标值自动停止并发 Completed，与外部中止的 Stopped 互斥

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: WaitingLobbyFeature——订阅注册、完成/中止分流与倒计时规则补全

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/game/arena/feature/WaitingLobbyFeature.kt`（整文件重写）
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/game/arena/feature/WaitingLobbyFeatureTest.kt`（新建）

**Interfaces:**
- Consumes（来自 Task 1，签名见 Task 1 Produces）: `Counter` 构造（named 参数 `interval/initialValue/step/stopTarget`）、`isRunning`、`start(scheduler)`、`stop()`、`reset()`、事件 `Started/Tick/Completed/Stopped`。
- Consumes（既有，不变）: `Feature<T>`、`FeatureKey`、`BridgeEvent.EntityDamageEvent(player, damage, finalDamage, damageSource, cause)`、`ArenaJoinedEvent.player` / `ArenaLeaveEvent.player`（**在玩家移除之后发出**，故处理器内 `playerCount()` 已不含离开者，直接 `< minPlayers` 比较无差一）、`Collection<Player>.sendMessage(text, block)`、`Player.sendActionBar`、`Duration.toTicks()`、`Long.ticks`、`Player.sendPackets`。
- Produces: `WaitingLobbyFeature<T>` 公开构造签名**完全不变**；行为契约变化仅为修复（详见各测试）。

- [ ] **Step 1: 写失败测试**

创建 `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/game/arena/feature/WaitingLobbyFeatureTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.game.arena.feature

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.game.arena.AbstractBukkitArena
import com.github.mayblock.easylib.impl.bukkit.game.arena.AbstractBukkitArenaEntity
import com.github.mayblock.easylib.impl.bukkit.game.arena.AbstractBukkitArenaPlayer
import com.github.mayblock.easylib.impl.bukkit.game.arena.bridge.BridgeEvent
import com.github.mayblock.easylib.impl.util.Counter
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** 手动泵调度器（不关心 Trigger 周期；checker 与 counter 任务在测试中同泵推进）。 */
private class PumpScheduler : TaskScheduler {
    private val tasks = LinkedHashMap<Int, TaskScheduler.Task>()
    private var nextId = 1
    val activeCount get() = tasks.size

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = nextId++
        tasks[id] = task
        return id
    }

    override fun cancelTask(taskId: Int): Boolean = tasks.remove(taskId) != null
    override fun cancelAllTasks() = tasks.clear()

    fun tick() {
        tasks.entries.toList().forEach { (id, task) ->
            if (id in tasks) task.onTick(object : TaskScheduler.TaskScope {
                override fun cancel() { tasks.remove(id) }
            })
        }
    }
}

class WaitingLobbyFeatureTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: Plugin
    private lateinit var pump: PumpScheduler
    private lateinit var arena: TestArena

    @BeforeTest
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        pump = PumpScheduler()
        arena = TestArena(plugin, pump)
        arena.isArenaEnabled = true
        // sendPackets 走 EasyLibApi.api 单例；注入 relaxed mock 使 HUD 包发送成为无害 no-op。
        EasyLibApi.api = mockk<BukkitEasyLib>(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        arena.isArenaEnabled = false
        MockBukkit.unmock()
    }

    private class TestPlayer(
        bukkitPlayer: Player,
        arena: BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>
    ) : AbstractBukkitArenaPlayer(bukkitPlayer, arena)

    private class TestEntity(bukkitEntity: Entity) : AbstractBukkitArenaEntity(bukkitEntity)

    class TestArena(plugin: Plugin, pump: PumpScheduler) :
        AbstractBukkitArena<TestPlayer, TestEntity>("waiting-lobby-test", plugin),
        TaskScheduler by pump {

        override fun createArenaEntity(entity: Entity): TestEntity = TestEntity(entity)
        override fun onDisableArena() {}
    }

    /** 倒计时 3 tick、minPlayers=2 的标准被测组合；counter 注入以便探测状态。 */
    private class Fixture(val arenaRef: () -> TestArena) {
        var completed = 0
        val counter = Counter(
            interval = 50.milliseconds,
            initialValue = 3,
            step = -1,
            stopTarget = 0,
        )
        val feature = WaitingLobbyFeature<TestArena>(
            minPlayers = 2,
            maxPlayers = 4,
            playerCount = { arenaRef().players.size },
            isActive = { true },
            startCountdown = 150.milliseconds,
            onComplete = { completed++ },
            counter = counter,
        )
    }

    private fun fixture() = Fixture { arena }

    private fun joinPlayer(): Pair<PlayerMock, TestPlayer> {
        val mock = server.addPlayer()
        val tp = TestPlayer(mock, arena)
        arena.addPlayer(tp)
        return mock to tp
    }

    private fun PlayerMock.drainMessages(): List<String> =
        generateSequence { nextMessage() }.toList()

    @Test
    fun `install 后监听立即生效--人数达标即启动倒计时并设 gamemode`() {
        val f = fixture()
        f.feature.onInstall(arena)

        val (p1, _) = joinPlayer()
        assertFalse(f.counter.isRunning, "1 人未达 minPlayers=2，不得启动")
        assertEquals(GameMode.ADVENTURE, p1.gameMode, "加入即应切到 ADVENTURE（旧 bug：监听从未注册）")

        val (p2, _) = joinPlayer()
        assertTrue(f.counter.isRunning, "2 人达标必须立即启动倒计时")
        assertTrue(p1.drainMessages().any { it.contains("游戏即将开始") })
        assertTrue(p2.drainMessages().any { it.contains("游戏即将开始") })
    }

    @Test
    fun `等待期间伤害事件被取消`() {
        val f = fixture()
        f.feature.onInstall(arena)
        val (_, tp1) = joinPlayer()

        val damage = BridgeEvent.EntityDamageEvent(
            tp1, 1.0, 1.0, mockk(relaxed = true),
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK
        )
        arena.emit(damage)
        assertTrue(damage.isCancelled)
    }

    @Test
    fun `倒计时中人数跌破下限--停止并复位并发人数不足消息`() {
        val f = fixture()
        f.feature.onInstall(arena)
        val (p1, _) = joinPlayer()
        val (_, tp2) = joinPlayer()
        pump.tick()   // Tick(2)：倒计时确认在走
        p1.drainMessages()

        arena.removePlayer(tp2)
        assertFalse(f.counter.isRunning, "人数 1 < minPlayers=2 必须中止倒计时")
        assertEquals(3, f.counter.get(), "中止后必须复位到满值，避免下次从残值继续数")
        assertEquals(0, f.completed, "中止不得触发 onComplete")
        assertTrue(p1.drainMessages().any { it.contains("人数不足") },
            "旧 bug：isRunning 恒 false 使该分支不可达")
    }

    @Test
    fun `倒计时数到 0--onComplete 恰好一次且无倒计时终止消息`() {
        val f = fixture()
        f.feature.onInstall(arena)
        val (p1, _) = joinPlayer()
        joinPlayer()
        p1.drainMessages()

        pump.tick()   // Tick(2)
        pump.tick()   // Tick(1)
        pump.tick()   // Tick(0) -> Completed

        assertEquals(1, f.completed)
        assertFalse(f.counter.isRunning)
        assertEquals(3, f.counter.get(), "完成后复位，供下一局复用")
        assertEquals(GameMode.SURVIVAL, p1.gameMode, "完成时恢复 gamemode")
        val msgs = p1.drainMessages()
        assertTrue(msgs.none { it.contains("倒计时终止") },
            "旧 bug：正常开赛也会收到'倒计时终止'")
    }

    @Test
    fun `先加人后安装--checker 轮询兜底启动`() {
        joinPlayer()
        joinPlayer()
        val f = fixture()
        f.feature.onInstall(arena)
        assertFalse(f.counter.isRunning, "安装瞬间无 join 事件，允许未启动")

        pump.tick()   // checker 跑一轮
        assertTrue(f.counter.isRunning, "旧 bug：只靠 join 事件触发，先加人后安装则永不启动")
    }

    @Test
    fun `uninstall 停止倒计时并清空全部调度任务`() {
        val f = fixture()
        f.feature.onInstall(arena)
        joinPlayer()
        joinPlayer()
        assertTrue(f.counter.isRunning)

        f.feature.onUninstall(arena)
        assertFalse(f.counter.isRunning, "旧 bug：卸载后幽灵倒计时任务继续跑")
        assertEquals(0, pump.activeCount, "checker 与 counter 任务都必须被取消")
    }

    @Test
    fun `倒计时 title 音效每人每 tick 恰好一次--无 n 平方广播`() {
        val f = fixture()
        f.feature.onInstall(arena)
        val (p1, _) = joinPlayer()
        val (p2, _) = joinPlayer()

        // 把计数器拨到 41：下一次 Tick 后 remaining = 40 tick = 2000ms，恰落在
        // 2 秒整秒边界（RED title 触发点）。旧实现 updateHud 内嵌 broadcast，
        // 2 人房这一帧每人会收到 2 次音效；修复后每人恰好 1 次。
        f.counter.set(41)
        val before1 = p1.heardSounds.size
        val before2 = p2.heardSounds.size
        pump.tick()
        assertEquals(1, p1.heardSounds.size - before1,
            "旧 bug：updateHud 内嵌 broadcast，音效次数随在线人数翻倍")
        assertEquals(1, p2.heardSounds.size - before2)
    }
}
```

注：若 `PlayerMock.heardSounds` 在当前 MockBukkit 版本不可用，改用 `player.assertSoundHeard(...)` / 自定义计数断言，断言口径不变（音效次数不随在线人数翻倍）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.game.arena.feature.WaitingLobbyFeatureTest"`
Expected: FAIL——至少 `install 后监听立即生效`、`人数跌破下限`、`数到 0`、`checker 兜底`、`uninstall` 五个用例失败（对应旧 bug 1/2/3/5/6）。

- [ ] **Step 3: 重写 WaitingLobbyFeature**

整文件替换 `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/game/arena/feature/WaitingLobbyFeature.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.game.arena.feature

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.feature.Feature
import com.github.mayblock.easylib.api.feature.FeatureKey
import com.github.mayblock.easylib.api.game.arena.event.ArenaJoinedEvent
import com.github.mayblock.easylib.api.game.arena.event.ArenaLeaveEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.game.arena.bridge.BridgeEvent
import com.github.mayblock.easylib.impl.bukkit.util.*
import com.github.mayblock.easylib.impl.util.Counter
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.entity.Player
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class WaitingLobbyFeature<T>(
    private val minPlayers: Int,
    private val maxPlayers: Int,
    private val playerCount: () -> Int,
    private val isActive: () -> Boolean,
    private val startCountdown: Duration,
    private val onComplete: () -> Unit,
    private val counter: Counter = Counter(
        interval = 1.ticks,
        initialValue = startCountdown.toTicks(),
        step = -1,
        stopTarget = 0,
    ),
) : Feature<T> where T : BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>, T : TaskScheduler {

    companion object Key : FeatureKey<WaitingLobbyFeature<*>>("WaitingLobbyFeature")

    private lateinit var arena: T
    private val onlinePlayers get() = arena.players.mapNotNull { it.bukkitPlayer }
    private val playerStatus get() = "${playerCount()}/$maxPlayers"
    private var disposer: Disposable? = null

    init {
        counter.on {
            on<Counter.Event.Started> {
                onlinePlayers.sendMessage("游戏即将开始！")
            }
            on<Counter.Event.Tick> {
                if (value == 0L) return@on   // 终点帧交给 Completed 收尾，不再刷 HUD
                val remaining = value.ticks
                onlinePlayers.forEach { it.updateCountdownHud(remaining) }
                broadcastCountdownTitle(remaining)   // 广播与 per-player 平级，只发一份
            }
            // 复位策略集中在两个终态处理器：Leave 只负责"决定停"，善后统一在这里。
            on<Counter.Event.Completed> {
                counter.reset()
                completeCountdown()
            }
            on<Counter.Event.Stopped> {
                counter.reset()
                val msg = if (playerCount() < minPlayers) {
                    "当前人数不足，需要等待更多玩家！"
                } else "倒计时终止"
                onlinePlayers.sendMessage(msg) {
                    it.resetCountdownHud()
                }
            }
        }
    }

    override fun onInstall(context: T) {
        arena = context
        // 立即订阅（旧 bug：订阅曾被误包进 Disposable lambda，整个生命周期从未注册）。
        val subscription = context.on {
            on<BridgeEvent.EntityDamageEvent> {
                if (!isActive()) return@on
                isCancelled = true
            }
            on<ArenaJoinedEvent> {
                if (!isActive()) return@on
                (player as BukkitArenaPlayer).bukkitPlayer?.gameMode = GameMode.ADVENTURE
                tryStartCountdown()
            }
            on<ArenaLeaveEvent> {
                if (!isActive()) return@on
                (player as BukkitArenaPlayer).bukkitPlayer?.let { p ->
                    p.gameMode = p.previousGameMode ?: GameMode.SURVIVAL
                }
                // ArenaLeaveEvent 在移除之后发出，playerCount() 已不含离开者，直接比较无差一。
                if (counter.isRunning && playerCount() < minPlayers) counter.stop()
            }
        }
        val checker = context.scheduleTask(TaskScheduler.Trigger.Interval(1.seconds)) {
            if (!isActive() || counter.isRunning) return@scheduleTask
            tryStartCountdown()   // 兜底：玩家先于安装到齐（或 join 早于 install）时补启动
            if (!counter.isRunning) onlinePlayers.sendActionBar("等待中 ($playerStatus)")
        }
        disposer = Disposable {
            subscription.dispose()
            context.cancelTask(checker)
        }
    }

    override fun onUninstall(context: T) {
        disposer?.dispose()
        disposer = null
        counter.stop()   // 旧 bug：卸载不停 counter，幽灵任务继续跑
    }

    /** 启动条件的唯一出处：join 事件（即时）与 checker（兜底）共用。 */
    private fun tryStartCountdown() {
        if (!counter.isRunning && playerCount() >= minPlayers) counter.start(arena)
    }

    private fun completeCountdown() {
        onlinePlayers.forEach { player ->
            player.gameMode = player.previousGameMode ?: GameMode.SURVIVAL
            player.resetCountdownHud()
        }
        onComplete()
    }

    // 倒计时用 level/exp 借位显示进度条；完成或中止时必须复位，否则玩家的经验条/等级 HUD 会残留倒计时数字。
    private fun Player.resetCountdownHud() {
        this.sendPackets {
            forPlayer {
                setExperience(exp, level, totalExperience)
            }
        }
    }

    private fun Player.updateCountdownHud(remaining: Duration) {
        val remainingSeconds = ceil(remaining.toDouble(DurationUnit.SECONDS)).toInt()
        this.sendPackets {
            forPlayer {
                setExperience((remaining / startCountdown).toFloat(), remainingSeconds, totalExperience)
            }
        }
        this.sendActionBar("${remainingSeconds}s 即将开始！ ($playerStatus)")
    }

    private fun broadcastCountdownTitle(remaining: Duration) {
        if (remaining.inWholeMilliseconds % 1000L != 0L) return   // 只在整秒边界触发
        val color = when (remaining.inWholeSeconds) {
            30L, 20L, 10L -> ChatColor.GREEN
            in 3L..5L -> ChatColor.YELLOW
            1L, 2L -> ChatColor.RED
            else -> return
        }
        val title = "$color${ChatColor.BOLD}${remaining.inWholeSeconds}"
        onlinePlayers.forEach { player ->
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            player.sendTitle(title, null, 0, 20, 0)
        }
    }
}
```

与旧实现的逐项对照：`onComplete` 升格为 val 字段并把私有方法改名 `completeCountdown()`（消除参数同名遮蔽）；`updateReadyHud`→`updateCountdownHud` 且**不再内嵌广播**（n² 修复）；`broadcastCountdownTitle` 改整秒判断（替代 Duration 精确相等的脆弱匹配，行为等价：1.ticks 步进下整秒帧恰好命中一次）；订阅移出 Disposable；Leave 补人数检查；`Completed`/`Stopped` 分流；checker 兜底启动；uninstall 停 counter。公开构造签名与 `Key` 完全不变。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.game.arena.feature.WaitingLobbyFeatureTest"`
Expected: 7 个测试全部 PASS。

- [ ] **Step 5: 全量回归**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL，全部既有测试不受影响。

- [ ] **Step 6: Commit**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/game/arena/feature/WaitingLobbyFeature.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/game/arena/feature/WaitingLobbyFeatureTest.kt
git commit -m "fix(arena): WaitingLobbyFeature 监听注册与倒计时生命周期修复

- 订阅移出 Disposable lambda：旧实现监听在整个生命周期从未注册，倒计时永不启动、伤害保护失效
- 接入 Counter 的 Completed/Stopped 分流：修复正常开赛瞬间误发'倒计时终止'，'人数不足'死分支恢复可达
- ArenaLeaveEvent 补人数跌破 minPlayers 的中止检查（事件在移除后发出，直接比较无差一）
- 倒计时 title/音效上移至 Tick 处理器平级广播，消除 n² 发送
- checker 兜底启动：修复玩家先于 feature 安装到齐时倒计时永不启动
- onUninstall 停止 counter，消除卸载后的幽灵倒计时任务

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 修复项 ↔ 任务/测试 对照表

| 审计问题 | 修复位置 | 锁定测试 |
|---|---|---|
| 1 监听未注册 | Task 2 onInstall | `install 后监听立即生效` / `伤害事件被取消` |
| 2 完成/中止语义混淆 | Task 1 Completed + Task 2 分流 | `数到 0--无倒计时终止消息` / Counter `Completed 而非 Stopped` |
| 3 人数跌破下限不停 | Task 2 Leave 处理器 | `人数跌破下限--停止并复位` |
| 4 n² 广播 | Task 2 广播上移 | `title 音效每人每 tick 至多一次` |
| 5 卸载不停 counter | Task 2 onUninstall | `uninstall 停止倒计时` |
| 6 先加人后安装卡死 | Task 2 checker 兜底 | `checker 轮询兜底启动` |
| 7 start/stop 竞态 | Task 1 锁化 | 由构造保证 + `重复 start 幂等` / `stop 后可重新 start` |
| 8 虚假 Stopped / Started 值不实 | Task 1 | `未启动时 stop 静默` / `Started 携带真实计数值` |

## 明确不做（Out of Scope，留待单独变更）

- `get()/set()` → `value` 属性等 API 风格整理（破坏性，需独立评审）。
- `ChatColor`/`sendTitle` → Adventure 迁移。
- 消息文案可配置化（i18n）。
- `playerCount` lambda 与 `arena.players` 双数据源统一（涉及构造签名）。
