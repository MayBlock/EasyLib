# Plan B：PlayerInventoryMenu → 独立的 PlayerOverlay API 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把基于数据包的 `PlayerInventoryMenu`（覆盖玩家自身背包窗口）从 MenuAPI 中剥离，独立为 `com.github.mayblock.easylib.api.bukkit.overlay` 下的 **PlayerOverlay** API：新接口不再继承 `Menu`，去掉 movable/placeable，把 `InteractEvent` 拆为 `OverlayClickEvent`+`OverlayInteractEvent`，把 `MenuOpen/CloseEvent` 换为独立的 `OverlayShow/HideEvent`，新增 `BukkitEasyLibApi.overlayFactory` 入口；迁移唯一调用方 `SpectatorService`；删除旧 player 类型；并顺带把实现类 `VirtualMenuManager` 改名为 `MenuManager`。

**Architecture:** 两个新包彻底承接旧 player 菜单的封包机制（保持 packet 语义、含异步刷新）：API 侧 `overlay`（`PlayerOverlay`/`PlayerOverlayFactory`/`OverlayEvent` 层/`PlayerOverlayScope`/`OverlaySlotScope`），impl 侧 `overlay`（`AbstractPlayerOverlay`←`AbstractVirtualMenu`、`PacketPlayerOverlay`←`VirtualPlayerInventoryMenu`、overlay 专属的 `OverlaySlotSpec`/`OverlaySlotBuilder`/`LiveSlot`/`SlotGrid`/`OverlayUpdateLoop`←`MenuUpdateLoop`、packet 版 `OverlayPacketExt`、`OverlayManager` 工厂）。采用**加法先行 + 末尾大爆炸删除**：先把整套 overlay 栈作为并行栈加出来（旧代码不动、每步测试绿），再切换入口/调用方，最后原子删除旧 player 簇。`isEmptyStack()`（chest 与 overlay 共用）留在 `menu.MenuExt`，只有真正 packet 专属的 `updateItem/updateCursorItem` 移入 overlay。

**Tech Stack:** Kotlin（工具链 25）、Spigot-API 26.1.2（compileOnly）、PacketEvents（`compileOnlyApi`，封包收发）、adventure（`compileOnly`）、JUnit + MockK + MockBukkit v26。事件层复用 `SimpleEventBus`/`EventListener`/`EventSource`（common）。

**Spec:** `docs/superpowers/specs/2026-07-05-menu-real-container-and-player-overlay-design.md`（已批准；本计划覆盖 **Part B / §5**）。Part A（§4，ChestMenu 真实容器化）已由 `2026-07-05-chest-menu-real-container.md` 实现完毕。

## Global Constraints

- 构建命令用 wrapper，且必须带 JDK 25：`JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew ...`（本机默认 java 为 1.8）。首个任务先跑一次确认 `JAVA_HOME` 路径存在。
- API/实现分离：`platform-bukkit-api` 不依赖 impl；所有新实现类为 `internal`（例外：`OverlayManager` 与既有 `VirtualMenuManager`/`MenuManager` 一致为 public，因它作为 `BukkitEasyLib.overlayFactory` 的推断类型需被 `close()` 调用）。
- **每个任务用 TDD，测试代码必须写进 `platform-bukkit-impl` 的 test 模块并通过**（用户硬性要求）。API 无 test 源集，API 类型的测试也放 impl。
- 依赖版本统一在 `gradle/libs.versions.toml`；仓库在 `buildsrc.convention.repos`。本计划**不新增依赖**。
- 注释/KDoc 用中文；conventional commits，结尾带 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 每个任务结束 `JAVA_HOME=... ./gradlew :platform-bukkit-impl:test` 必须绿。
- GPG 签名可能需交互解锁：实现子代理 **stage（`git add`）后不要 commit**，由控制器提交（避免非交互环境卡住）。步骤末尾只写 stage。
- 采用**加法先行**：Task 1–7 纯新增 overlay 栈，旧 player 代码原封不动，全程编译+测试绿；Task 8 切换入口/调用方；Task 9 原子删除旧簇。overlay 的 `LiveSlot`/`SlotGrid` 与旧 `menu` 包的同名类在 Task 3–8 期间以不同包共存，Task 9 删旧，无永久重复。
- 已核实可复用的既有 API（勿重造，签名逐一确认过）：
  - 事件：`interface Event`（`Event.Cancellable { var isCancelled }`）；`class EventListener<T : Event>(type: Class<out T>, group: String?, handler: T.()->Unit, priority: Priority)`；`interface EventSource<E : Event>{ subscribe/unsubscribe/unsubscribeGroup/unsubscribeAll }`；`inline fun <reified T : Event> EventSource<in T>.on(group?, block: EventScope<T>.()->Unit): Disposable`，内层 `EventScope.on<reified T>(priority){}`。
  - `class SimpleEventBus<E : Event>(listeners = emptyList()) : EventBus<E>`：`subscribe`、`emit`（按 `type.isInstance` 过滤并吞异常记日志）、`unsubscribeAll`；可 `by bus` 委托 `EventSource`。
  - `interface Destroyable { val isDestroyed: Boolean; fun destroy() }`；`data class Priority(val priority: Int)`，`Priority.DEFAULT = Priority(10)`；`fun interface Disposable { fun dispose() }`。
  - `interface TaskScheduler`：`scheduleTask(task: Task): Int`、`scheduleTask(builder: TaskBuilder.()->Unit): Int`、`cancelTask(id): Boolean`；`Task { val trigger: Trigger; val isAsync: Boolean; val onTick: ()->Unit }`；`sealed interface Trigger { object Once; class Delay(delay); class Interval(period) }`；`TaskBuilder { var trigger; var isAsync; var onTick }`。
  - `PacketScope.PlayerPacketScope`：`containerSetSlot(windowId, stateId, slot, item: ItemStack?)`、`containerItems(windowId, stateId, items: List<ItemStack?>, carriedItem=null)`、`camera(...)` 等。
  - impl 工具（保持导入路径）：`com.github.mayblock.easylib.impl.bukkit.util.sendPackets`（`player.sendPackets { forPlayer { } }`）、`...util.fromBukkit`（`org.bukkit ItemStack → packetevents ItemStack`）、`...packet.extension.getBukkitClickType`（`WrapperPlayClientClickWindow.getBukkitClickType(): ClickType`）、`com.github.mayblock.easylib.impl.util.extension.ifTrue`、`com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack`（**留在 menu 包，chest 也用**）。
  - `BukkitEasyLib.api.packetManager.registerListener(PacketListener): Disposable`（`BukkitEasyLib.Companion.api`）。
  - 测试：`MockBukkit.mock()/unmock()`、`server.addPlayer()`、`MockBukkit.createMockPlugin()`、`mockk`/`relaxed`；假调度器 `AsyncTrackingScheduler`（见 `RealChestMenuUpdateTest`）立即同步执行 `onTick`。PacketEvents 在单测环境**不可用**，故 `packetItem()`/构造 `PacketPlayerOverlay`/封包渲染路径**不做单测**（spec §6：手动验证包层渲染）。

## 目标包结构

```
platform-bukkit-api/.../api/bukkit/overlay/
  PlayerOverlay.kt            接口：show/hide/getItem/setItem/OVERLAY_SIZE，不继承 Menu
  PlayerOverlayFactory.kt     create(builder): PlayerOverlay
  OverlayEvent.kt             OverlayEvent(val overlay) + OverlayShowEvent + OverlayHideEvent
  OverlaySlotEvent.kt         OverlaySlotEvent(val index) + OverlayClickEvent + OverlayInteractEvent + OverlayUpdateEvent
  dsl/PlayerOverlayDsl.kt     @PlayerOverlayDsl + PlayerOverlayScope + Material 便捷扩展
  dsl/OverlaySlotScope.kt     OverlaySlotScope: onClick/onInteract/onUpdate

platform-bukkit-impl/.../impl/bukkit/overlay/
  OverlaySlotSpec.kt          OverlaySlotSpec/OverlayHandler/OverlayUpdateRule（无 movable/placeable）
  OverlaySlotBuilder.kt       实现 OverlaySlotScope → OverlaySlotSpec
  LiveSlot.kt                 overlay 私有运行态（无 movable/placeable）
  SlotGrid.kt                 overlay 私有槽网格
  OverlayUpdateLoop.kt        ← MenuUpdateLoop（isAsync=true 保留）
  OverlayPacketExt.kt         ← MenuExt 的 updateItem/updateCursorItem
  AbstractPlayerOverlay.kt    ← AbstractVirtualMenu（bus<OverlayEvent>、show/hide 事件、getItem/setItem、destroy）
  PacketPlayerOverlay.kt      ← VirtualPlayerInventoryMenu（windowId=0、封包收发、click/interact/drop）
  PlayerOverlayBuilder.kt     ← PlayerMenuBuilder（工厂 lambda → PlayerOverlay）
  OverlayManager.kt           PlayerOverlayFactory + Closeable：create/track/destroy
```

改：`BukkitEasyLibApi`(+overlayFactory)、`BukkitEasyLib`(+wiring、rename、close)、`MenuFactory`(−createPlayerInventoryMenu)、`SpectatorService`(迁移)、`VirtualMenuManager`→`MenuManager`(rename、−player 方法)、`MenuExt`(−packet ext、留 isEmptyStack)。
删：api `PlayerInventoryMenu`/`InteractEvent`/`dsl/PlayerMenuDsl`；impl `VirtualPlayerInventoryMenu`/`builder/PlayerMenuBuilder`/`AbstractVirtualMenu`/`MenuUpdateLoop`/`menu/SlotGrid`/`slot/LiveSlot`/`VirtualMenu`；test `AbstractVirtualMenuTest`。

---

### Task 1：PlayerOverlay 接口 + 独立事件层（API）

**Files:**
- Create: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/overlay/PlayerOverlay.kt`
- Create: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/overlay/OverlayEvent.kt`
- Create: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/overlay/OverlaySlotEvent.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayEventTest.kt`

**Interfaces:**
- Consumes: `com.github.mayblock.easylib.api.event.Event`、`EventSource`、`com.github.mayblock.easylib.api.util.Destroyable`、`org.bukkit.entity.Player`、`org.bukkit.event.inventory.ClickType`、`org.bukkit.inventory.ItemStack`。
- Produces（后续 Task 依赖，签名务必一致）:
  - `interface PlayerOverlay : Destroyable, EventSource<OverlayEvent> { fun show(player: Player); fun hide(player: Player): Boolean; fun getItem(index: Int): ItemStack?; fun setItem(index: Int, item: ItemStack?); companion object { const val OVERLAY_SIZE = 46 } }`
  - `interface OverlayEvent : Event { val overlay: PlayerOverlay }`；`class OverlayShowEvent(overlay, player)`；`class OverlayHideEvent(overlay, player)`
  - `interface OverlaySlotEvent : OverlayEvent { val index: Int }`；`class OverlayClickEvent(overlay, index, player, val type: ClickType)`；`class OverlayInteractEvent(overlay, index, player, val action: Action) { enum class Action { LEFT_CLICK, RIGHT_CLICK } }`；`class OverlayUpdateEvent(overlay, index, var item: ItemStack)`

- [ ] **Step 1: 写失败测试**

创建 `OverlayEventTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlaySlotEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.event.EventListener
import com.github.mayblock.easylib.api.util.Priority
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import io.mockk.mockk
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayEventTest {

    private val overlay = mockk<PlayerOverlay>()
    private val player = mockk<Player>()

    @Test
    fun `click 与 interact 事件暴露 index 与各自字段`() {
        val click = OverlayClickEvent(overlay, 3, player, ClickType.LEFT)
        val interact = OverlayInteractEvent(overlay, 4, player, OverlayInteractEvent.Action.RIGHT_CLICK)
        assertEquals(3, click.index)
        assertEquals(ClickType.LEFT, click.type)
        assertEquals(4, interact.index)
        assertEquals(OverlayInteractEvent.Action.RIGHT_CLICK, interact.action)
    }

    @Test
    fun `SimpleEventBus 按事件类型过滤派发 OverlaySlotEvent`() {
        val bus = SimpleEventBus<OverlayEvent>()
        val clicks = mutableListOf<Int>()
        bus.subscribe(
            EventListener<OverlaySlotEvent>(
                OverlayClickEvent::class.java, null, { clicks += index }, Priority.DEFAULT,
            )
        )
        bus.emit(OverlayClickEvent(overlay, 3, player, ClickType.LEFT))
        bus.emit(OverlayInteractEvent(overlay, 4, player, OverlayInteractEvent.Action.LEFT_CLICK))
        assertEquals(listOf(3), clicks) // interact 不匹配 click 监听
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: 编译失败，`unresolved reference: overlay`（类型尚不存在）。

- [ ] **Step 3: 写 `PlayerOverlay.kt`**

```kotlin
package com.github.mayblock.easylib.api.bukkit.overlay

import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.util.Destroyable
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 玩家背包覆盖层：用数据包在玩家自己的背包窗口上叠加虚拟显示并捕获交互，**非真实容器**。
 * 只做「展示 + 交互」，不与真实背包做物品转移（故无 movable/placeable）。
 *
 * 对外只暴露订阅侧事件源（[EventSource]），可监听 [OverlayEvent]（show/hide、slot 点击/交互）。
 * 实例只应经 [PlayerOverlayFactory] 创建。
 */
interface PlayerOverlay : Destroyable, EventSource<OverlayEvent> {
    /** 对该玩家开启覆盖层（发送初始虚拟物品并登记观察者）。 */
    fun show(player: Player)

    /** 对该玩家关闭覆盖层并还原真实背包渲染；此前未开启返回 false。 */
    fun hide(player: Player): Boolean

    /** 某声明槽位的当前虚拟物品；未声明或为空（AIR/数量≤0）返回 null。 */
    fun getItem(index: Int): ItemStack?

    /**
     * 改写某声明槽位的虚拟物品并重绘给所有观察者；`null` 等价清空（AIR）。
     * @throws IllegalArgumentException 槽位未在构建时声明
     */
    fun setItem(index: Int, item: ItemStack?)

    companion object {
        /** 覆盖层窗口格数：玩家背包窗口的 46 格（合成格 + 盔甲 + 主背包 + 热键栏 + 副手）。 */
        const val OVERLAY_SIZE = 46
    }
}
```

- [ ] **Step 4: 写 `OverlayEvent.kt`**

```kotlin
package com.github.mayblock.easylib.api.bukkit.overlay

import com.github.mayblock.easylib.api.event.Event
import org.bukkit.entity.Player

/** 覆盖层事件根类型（独立于菜单事件层 `MenuEvent`）。 */
interface OverlayEvent : Event {
    val overlay: PlayerOverlay
}

/** 覆盖层对某玩家开启时派发。 */
class OverlayShowEvent(
    override val overlay: PlayerOverlay,
    val player: Player,
) : OverlayEvent

/** 覆盖层对某玩家关闭（含断线）时派发。 */
class OverlayHideEvent(
    override val overlay: PlayerOverlay,
    val player: Player,
) : OverlayEvent
```

- [ ] **Step 5: 写 `OverlaySlotEvent.kt`**

```kotlin
package com.github.mayblock.easylib.api.bukkit.overlay

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

/** 与某槽位相关的覆盖层事件。 */
interface OverlaySlotEvent : OverlayEvent {
    val index: Int
}

/** 玩家在背包窗口内点击某覆盖槽位时派发（带 Bukkit [ClickType]）。 */
class OverlayClickEvent(
    override val overlay: PlayerOverlay,
    override val index: Int,
    val player: Player,
    val type: ClickType,
) : OverlaySlotEvent

/** 玩家手持某覆盖槽位物品挥动/使用时派发（左/右键）。 */
class OverlayInteractEvent(
    override val overlay: PlayerOverlay,
    override val index: Int,
    val player: Player,
    val action: Action,
) : OverlaySlotEvent {
    enum class Action {
        LEFT_CLICK,
        RIGHT_CLICK,
    }
}

/** 更新循环按 update rule 触发；回调改写 [item] 后引擎重绘该槽（无 player，广播语义）。 */
class OverlayUpdateEvent(
    override val overlay: PlayerOverlay,
    override val index: Int,
    var item: ItemStack,
) : OverlaySlotEvent
```

- [ ] **Step 6: 跑测试确认通过**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.overlay.OverlayEventTest"`
Expected: PASS（2 个测试）。

- [ ] **Step 7: 全量测试 + stage**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿（旧代码未动）。
`git add platform-bukkit-api/.../overlay/ platform-bukkit-impl/.../overlay/OverlayEventTest.kt`

---

### Task 2：Overlay 槽声明模型 + DSL（OverlaySlotSpec / OverlaySlotBuilder / Scope）

**Files:**
- Create: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/overlay/dsl/PlayerOverlayDsl.kt`
- Create: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/overlay/dsl/OverlaySlotScope.kt`
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlaySlotSpec.kt`
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlaySlotBuilder.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlaySlotBuilderTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `OverlayClickEvent`/`OverlayInteractEvent`/`OverlayUpdateEvent`/`OverlaySlotEvent`；`Priority`、`TaskScheduler.Trigger`、`ItemStack`、`ItemMeta`。
- Produces:
  - `@PlayerOverlayDsl`（DslMarker）；`interface PlayerOverlayScope { fun slot(index/range, item, metadata, block: (OverlaySlotScope.()->Unit)? ) }` + `Material` 便捷扩展。
  - `interface OverlaySlotScope { fun onClick(priority, block: OverlayClickEvent.()->Unit); fun onInteract(priority, block: OverlayInteractEvent.()->Unit); fun onUpdate(trigger, priority, block: OverlayUpdateEvent.()->Unit) }`
  - `internal class OverlaySlotSpec(item, handlers: List<OverlayHandler>, updateRules: List<OverlayUpdateRule>)`；`internal class OverlayHandler(priority, type: Class<out OverlaySlotEvent>, block: OverlaySlotEvent.()->Unit)`；`internal class OverlayUpdateRule(trigger, block: OverlayUpdateEvent.()->Unit)`
  - `internal class OverlaySlotBuilder : OverlaySlotScope { fun build(item): OverlaySlotSpec }`

- [ ] **Step 1: 写失败测试**

创建 `OverlaySlotBuilderTest.kt`（镜像 `SlotBuilderTest`）：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlaySlotBuilderTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test
    fun `onClick 与 onInteract 以对应事件类型收集为 handler`() {
        val spec = OverlaySlotBuilder().apply {
            onClick { }
            onInteract { }
        }.build(item(Material.STONE))
        assertEquals(
            listOf<Class<*>>(OverlayClickEvent::class.java, OverlayInteractEvent::class.java),
            spec.handlers.map { it.type },
        )
    }

    @Test
    fun `onUpdate 收集为 updateRule`() {
        val spec = OverlaySlotBuilder().apply {
            onUpdate(trigger = TaskScheduler.Trigger.Once) { }
        }.build(item(Material.STONE))
        assertEquals(1, spec.updateRules.size)
        assertEquals(0, spec.handlers.size)
    }

    @Test
    fun `build 透传初始物品`() {
      val spec = OverlaySlotBuilder().build(item(Material.DIAMOND, 3))
        assertEquals(Material.DIAMOND, spec.item.type)
        assertEquals(3, spec.item.amount)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: 编译失败，`unresolved reference: OverlaySlotBuilder`。

- [ ] **Step 3: 写 `OverlaySlotScope.kt`**

```kotlin
package com.github.mayblock.easylib.api.bukkit.overlay.dsl

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority

/**
 * 覆盖层单槽 DSL：仅展示 + 交互，**无 movable/placeable、无 take/place**。
 * 背包窗口内点击 → [onClick]（[OverlayClickEvent]）；手持挥动/使用 → [onInteract]（[OverlayInteractEvent]）；
 * 定时刷新 → [onUpdate]（[OverlayUpdateEvent]，仍异步）。
 */
@PlayerOverlayDsl
interface OverlaySlotScope {
    fun onClick(priority: Priority = Priority.DEFAULT, block: OverlayClickEvent.() -> Unit)
    fun onInteract(priority: Priority = Priority.DEFAULT, block: OverlayInteractEvent.() -> Unit)
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: OverlayUpdateEvent.() -> Unit)
}
```

- [ ] **Step 4: 写 `PlayerOverlayDsl.kt`**

```kotlin
package com.github.mayblock.easylib.api.bukkit.overlay.dsl

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

@DslMarker
annotation class PlayerOverlayDsl

/** 覆盖层整体 DSL：按 index/range 声明覆盖槽位。 */
@PlayerOverlayDsl
interface PlayerOverlayScope {
    fun slot(
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit = {},
        block: (OverlaySlotScope.() -> Unit)? = null,
    )
    fun slot(
        range: IntRange,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit = {},
        block: (OverlaySlotScope.() -> Unit)? = null,
    )
}

fun PlayerOverlayScope.slot(
    index: Int,
    type: Material,
    amount: Int = 1,
    metadata: ItemMeta.() -> Unit = {},
    block: (OverlaySlotScope.() -> Unit)? = null,
) {
  slot(index, item(type, amount), metadata, block)
}

fun PlayerOverlayScope.slot(
    range: IntRange,
    type: Material,
    amount: Int = 1,
    metadata: ItemMeta.() -> Unit = {},
    block: (OverlaySlotScope.() -> Unit)? = null,
) {
  slot(range, item(type, amount), metadata, block)
}
```

- [ ] **Step 5: 写 `OverlaySlotSpec.kt`**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlaySlotEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 覆盖槽的不可变声明：初始物品 + 点击/交互处理器 + 更新规则。零运行态；无 movable/placeable。
 * 由 [OverlaySlotBuilder] 产出，运行期对应物是 [LiveSlot]。
 */
internal class OverlaySlotSpec(
    val item: ItemStack,
    val handlers: List<OverlayHandler>,
    val updateRules: List<OverlayUpdateRule>,
)

/** 点击/交互处理器，按事件类型（[OverlayClickEvent]/[OverlayInteractEvent]）标注，注册时按 `type.isInstance` 过滤。 */
internal class OverlayHandler(
    val priority: Priority,
    val type: Class<out OverlaySlotEvent>,
    val block: OverlaySlotEvent.() -> Unit,
)

internal class OverlayUpdateRule(
    val trigger: TaskScheduler.Trigger,
    val block: OverlayUpdateEvent.() -> Unit,
)
```

- [ ] **Step 6: 写 `OverlaySlotBuilder.kt`**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlaySlotEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayUpdateEvent
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.OverlaySlotScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 实现 api 的 [OverlaySlotScope]，把用户声明收集成不可变的 [OverlaySlotSpec]。纯声明、无运行态、无总线。
 *
 * 处理器以事件类型标注——会注册到覆盖层总线并按 `type.isInstance` 过滤，
 * 因此把 `OverlayClickEvent.()->Unit` 当作 `OverlaySlotEvent.()->Unit` 存储是安全的。
 */
internal class OverlaySlotBuilder : OverlaySlotScope {

    private val handlers = mutableListOf<OverlayHandler>()
    private val updates = mutableListOf<OverlayUpdateRule>()

    override fun onClick(priority: Priority, block: OverlayClickEvent.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        handlers += OverlayHandler(priority, OverlayClickEvent::class.java, block as OverlaySlotEvent.() -> Unit)
    }

    override fun onInteract(priority: Priority, block: OverlayInteractEvent.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        handlers += OverlayHandler(priority, OverlayInteractEvent::class.java, block as OverlaySlotEvent.() -> Unit)
    }

    override fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority, block: OverlayUpdateEvent.() -> Unit) {
        updates += OverlayUpdateRule(trigger, block)
    }

    fun build(item: ItemStack): OverlaySlotSpec =
        OverlaySlotSpec(item, handlers.toList(), updates.toList())
}
```

- [ ] **Step 7: 跑测试确认通过 + 全量 + stage**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿（含 `OverlaySlotBuilderTest` 3 个）。
`git add platform-bukkit-api/.../overlay/dsl/ platform-bukkit-impl/.../overlay/OverlaySlotSpec.kt platform-bukkit-impl/.../overlay/OverlaySlotBuilder.kt platform-bukkit-impl/.../overlay/OverlaySlotBuilderTest.kt`

---

### Task 3：Overlay 槽运行态（LiveSlot + SlotGrid）

**Files:**
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/LiveSlot.kt`
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/SlotGrid.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlaySlotGridTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `OverlaySlotSpec`/`OverlayHandler`/`OverlayUpdateRule`；`com.github.mayblock.easylib.impl.bukkit.util.fromBukkit`；`com.github.retrooper.packetevents.protocol.item.ItemStack`。
- Produces:
  - `internal class LiveSlot(spec: OverlaySlotSpec) { @Volatile var item; val handlers; val updateRules; fun packetItem(): ItemStack }`
  - `internal class SlotGrid(specs: Map<Int, OverlaySlotSpec>) { operator fun get(index): LiveSlot?; fun packetItem(index): ItemStack; fun packetItems(size): List<ItemStack?>; fun forEachUpdatable(action) }`

> 注：`packetItem()` 依赖 PacketEvents（`fromBukkit`），单测环境不可用，故测试只覆盖 `get`/`forEachUpdatable`/字段透传，不触 `packetItem`（与既有做法一致）。

- [ ] **Step 1: 写失败测试**

创建 `OverlaySlotGridTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class OverlaySlotGridTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun spec(block: OverlaySlotBuilder.() -> Unit = {}) =
      OverlaySlotBuilder().apply(block).build(item(Material.STONE))

    @Test
    fun `get 返回声明槽的 LiveSlot，未声明返回 null`() {
        val grid = SlotGrid(mapOf(2 to spec()))
        assertEquals(Material.STONE, grid[2]!!.item.type)
        assertNull(grid[5])
    }

    @Test
    fun `forEachUpdatable 只遍历带 update rule 的槽`() {
        val grid = SlotGrid(
            mapOf(
                1 to spec(),
                2 to spec { onUpdate(TaskScheduler.Trigger.Once) { } },
            )
        )
        val visited = mutableListOf<Int>()
        grid.forEachUpdatable { index, _ -> visited += index }
        assertEquals(listOf(2), visited)
    }

    @Test
    fun `LiveSlot item 可变且初值为 spec 物品`() {
        val s = LiveSlot(spec())
        assertEquals(Material.STONE, s.item.type)
      val diamond = item(Material.DIAMOND)
        s.item = diamond
        assertSame(diamond, s.item)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: 编译失败，`unresolved reference: SlotGrid`/`LiveSlot`。

- [ ] **Step 3: 写 `LiveSlot.kt`**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import com.github.retrooper.packetevents.protocol.item.ItemStack

/**
 * 覆盖槽的运行态：可变当前物品 + packet 物品缓存。[OverlaySlotSpec] 的运行期对应物。
 *
 * [item] 标 `@Volatile`：更新任务在异步线程写、渲染读，需保证可见性。
 */
internal class LiveSlot(private val spec: OverlaySlotSpec) {

    @Volatile
    var item: org.bukkit.inventory.ItemStack = spec.item

    val handlers: List<OverlayHandler> get() = spec.handlers
    val updateRules: List<OverlayUpdateRule> get() = spec.updateRules

    private var lastBukkitItem: org.bukkit.inventory.ItemStack? = null
    // 首次 packetItem() 时才转换——避免构造期触发 PacketEvents 静态初始化（ItemStack.EMPTY 需活的 PacketEvents API）。
    private var cachedPacketItem: ItemStack? = null

    fun packetItem(): ItemStack {
        val current = item
        if (current === lastBukkitItem) return cachedPacketItem!!
        if (current == lastBukkitItem) {
            lastBukkitItem = current
            return cachedPacketItem!!
        }
        val packet = current.fromBukkit()
        cachedPacketItem = packet
        lastBukkitItem = current
        return packet
    }
}
```

- [ ] **Step 4: 写 `SlotGrid.kt`**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.retrooper.packetevents.protocol.item.ItemStack

/** 覆盖层槽集合：从不可变 [OverlaySlotSpec] 映射出运行态 [LiveSlot]。 */
internal class SlotGrid(specs: Map<Int, OverlaySlotSpec>) {

    private val slots: Map<Int, LiveSlot> = specs.mapValues { LiveSlot(it.value) }

    operator fun get(index: Int): LiveSlot? = slots[index]

    /** 某槽 packet 物品；未定义返回 [ItemStack.EMPTY]。 */
    fun packetItem(index: Int): ItemStack = slots[index]?.packetItem() ?: ItemStack.EMPTY

    /** `[0, size)` 全量 packet 物品，未定义处为 null（用于 WindowItems/ContainerItems）。 */
    fun packetItems(size: Int): List<ItemStack?> = List(size) { slots[it]?.packetItem() }

    fun forEachUpdatable(action: (index: Int, slot: LiveSlot) -> Unit) =
        slots.forEach { (index, slot) -> if (slot.updateRules.isNotEmpty()) action(index, slot) }
}
```

- [ ] **Step 5: 跑测试 + 全量 + stage**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿（含 `OverlaySlotGridTest` 3 个）。
`git add platform-bukkit-impl/.../overlay/LiveSlot.kt platform-bukkit-impl/.../overlay/SlotGrid.kt platform-bukkit-impl/.../overlay/OverlaySlotGridTest.kt`

---

### Task 4：OverlayUpdateLoop（← MenuUpdateLoop，isAsync 保留）

**Files:**
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayUpdateLoop.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayUpdateLoopTest.kt`

**Interfaces:**
- Consumes: Task 1 `PlayerOverlay`/`OverlayUpdateEvent`；Task 3 `SlotGrid`；`TaskScheduler`。
- Produces: `internal class OverlayUpdateLoop(overlay: PlayerOverlay, grid: SlotGrid, scheduler: TaskScheduler, repaint: (Int)->Unit) { fun start(); fun stop() }`（`isAsync = true`）。

- [ ] **Step 1: 写失败测试**（用 `AsyncTrackingScheduler` 立即执行 onTick）

创建 `OverlayUpdateLoopTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** 立即同步执行每个被排任务一次的假调度器（并记录 isAsync）。 */
private class AsyncTrackingScheduler(val asyncFlags: MutableList<Boolean> = mutableListOf()) : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int { asyncFlags += task.isAsync; task.onTick(); return 0 }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

class OverlayUpdateLoopTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test
    fun `update 规则异步 tick 并在物品变化时重绘`() {
        val scheduler = AsyncTrackingScheduler()
        val spec = OverlaySlotBuilder().apply {
          onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item = item(Material.CLOCK, 5) }
        }.build(item(Material.AIR))
        val grid = SlotGrid(mapOf(4 to spec))
        val repaints = mutableListOf<Int>()
        OverlayUpdateLoop(mockk<PlayerOverlay>(), grid, scheduler, { repaints += it }).start()

        assertEquals(Material.CLOCK, grid[4]!!.item.type)
        assertEquals(listOf(4), repaints)
        assertEquals(listOf(true), scheduler.asyncFlags) // overlay 保持异步
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: 编译失败，`unresolved reference: OverlayUpdateLoop`。

- [ ] **Step 3: 写 `OverlayUpdateLoop.kt`**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayUpdateEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.scheduler.TaskScheduler

/**
 * 覆盖槽更新调度：每个 [OverlayUpdateRule] 各按自己的 trigger 排程。
 * 与覆盖层类型解耦——只通过 [repaint] 回调把变更同步给观察者。overlay 纯发包，**保持异步**。
 */
internal class OverlayUpdateLoop(
    private val overlay: PlayerOverlay,
    private val grid: SlotGrid,
    private val scheduler: TaskScheduler,
    private val repaint: (index: Int) -> Unit,
) {
    private val taskIds = mutableListOf<Int>()

    fun start() {
        grid.forEachUpdatable { index, slot ->
            slot.updateRules.forEach { rule ->
                taskIds += scheduler.scheduleTask {
                    trigger = rule.trigger
                    isAsync = true
                    onTick = {
                        val event = OverlayUpdateEvent(overlay, index, slot.item.clone()).apply(rule.block)
                        if (event.item != slot.item) {
                            slot.item = event.item
                            repaint(index)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        taskIds.forEach(scheduler::cancelTask)
        taskIds.clear()
    }
}
```

- [ ] **Step 4: 跑测试 + 全量 + stage**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿（含 `OverlayUpdateLoopTest`）。
`git add platform-bukkit-impl/.../overlay/OverlayUpdateLoop.kt platform-bukkit-impl/.../overlay/OverlayUpdateLoopTest.kt`

---

### Task 5：AbstractPlayerOverlay（基类）+ packet 扩展迁入

**Files:**
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayPacketExt.kt`
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/AbstractPlayerOverlay.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/AbstractPlayerOverlayTest.kt`（镜像 `AbstractVirtualMenuTest`）

**Interfaces:**
- Consumes: `PlayerOverlay`/`OverlayEvent`/`OverlayShowEvent`/`OverlayHideEvent`/`OverlaySlotEvent`；`SimpleEventBus`/`EventListener`/`EventSource`；`TaskScheduler`；`Disposable`；Task 3 `SlotGrid`；Task 4 `OverlayUpdateLoop`；`com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack`（**留在 menu 包**）；`PacketScope.PlayerPacketScope`。
- Produces:
  - `OverlayPacketExt.kt`：`internal fun PacketScope.PlayerPacketScope.updateCursorItem(item: ItemStack?)`；`internal fun PacketScope.PlayerPacketScope.updateItem(windowId: Int, slot: Int, item: ItemStack)`。
  - `internal abstract class AbstractPlayerOverlay(scheduler, specs: Map<Int, OverlaySlotSpec>, bus = SimpleEventBus()) : PlayerOverlay, EventSource<OverlayEvent> by bus`，含 `protected val grid`、`activeViewers`、`getItem`/`setItem`、`publish`/`addViewer`/`removeViewer`、`startOverlay()`、`destroy()`；抽象 `repaint`/`registerPacketListener`，open `onHide`。

- [ ] **Step 1: 写失败测试**

创建 `AbstractPlayerOverlayTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 不调用 startOverlay 的最小实现：不触碰 PacketEvents/调度器，纯测 grid 访问、总线派发、生命周期。 */
private class TestOverlay(
    specs: Map<Int, OverlaySlotSpec>,
) : AbstractPlayerOverlay(mockk<TaskScheduler>(relaxed = true), specs) {
    val repaints = mutableListOf<Int>()
    override fun repaint(index: Int) { repaints += index }
    override fun registerPacketListener(): Disposable = Disposable { }
    override fun show(player: Player) {}
    override fun hide(player: Player): Boolean = false
    fun emit(event: OverlayEvent) = publish(event)
}

class AbstractPlayerOverlayTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun specOf(item: ItemStack, block: OverlaySlotBuilder.() -> Unit = {}): OverlaySlotSpec =
        OverlaySlotBuilder().apply(block).build(item)

    @Test
    fun `getItem 返回声明槽当前物品，未声明或 AIR 返回 null`() {
      val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE, 5)), 4 to specOf(item(Material.AIR))))
        assertEquals(Material.STONE, o.getItem(3)!!.type)
        assertEquals(5, o.getItem(3)!!.amount)
        assertNull(o.getItem(4))
        assertNull(o.getItem(9))
    }

    @Test
    fun `setItem 写入声明槽并触发 repaint，null 等价 AIR`() {
      val o = TestOverlay(mapOf(3 to specOf(item(Material.AIR))))
      o.setItem(3, item(Material.DIAMOND, 2))
        assertEquals(Material.DIAMOND, o.getItem(3)!!.type)
        o.setItem(3, null)
        assertNull(o.getItem(3))
        assertEquals(listOf(3, 3), o.repaints)
    }

    @Test
    fun `setItem 对未声明槽抛 IllegalArgumentException`() {
      val o = TestOverlay(mapOf(3 to specOf(item(Material.AIR))))
      assertFailsWith<IllegalArgumentException> { o.setItem(4, item(Material.DIAMOND)) }
    }

    @Test
    fun `onClick 声明经总线按 index 过滤派发`() {
        var clicks = 0
      val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE)) { onClick { clicks++ } }))
        val player = mockk<Player>(relaxed = true)
        o.emit(OverlayClickEvent(o, 3, player, ClickType.LEFT))
        o.emit(OverlayClickEvent(o, 4, player, ClickType.LEFT))
        assertEquals(1, clicks)
    }

    @Test
    fun `destroy 后 isDestroyed 为真`() {
      val o = TestOverlay(mapOf(3 to specOf(item(Material.STONE))))
        assertTrue(!o.isDestroyed)
        o.destroy()
        assertTrue(o.isDestroyed)
    }

    @Test
    fun `isEmptyStack 判定 null、AIR 与非空`() {
        assertTrue((null as ItemStack?).isEmptyStack())
      assertTrue(item(Material.AIR).isEmptyStack())
      assertTrue(!item(Material.STONE).isEmptyStack())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: 编译失败，`unresolved reference: AbstractPlayerOverlay`。

- [ ] **Step 3: 写 `OverlayPacketExt.kt`**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.packetevents.packet.dsl.PacketScope
import com.github.retrooper.packetevents.protocol.item.ItemStack

/** 清空/改写光标槽（windowId -1 为光标）。 */
internal fun PacketScope.PlayerPacketScope.updateCursorItem(item: ItemStack?) {
    containerSetSlot(-1, 0, -1, item)
}

/** 改写指定窗口某槽物品。 */
internal fun PacketScope.PlayerPacketScope.updateItem(windowId: Int, slot: Int, item: ItemStack) {
    containerSetSlot(windowId, 0, slot, item)
}
```

- [ ] **Step 4: 写 `AbstractPlayerOverlay.kt`**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayHideEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayShowEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlaySlotEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.event.EventListener
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 覆盖层共用机制：覆盖层级事件总线（仅暴露订阅侧）、观察者集合、槽网格、更新循环、
 * 包监听生命周期、槽事件派发与销毁模板。具体覆盖层只实现各自差异（show/hide、包映射、重绘）。
 */
internal abstract class AbstractPlayerOverlay(
    scheduler: TaskScheduler,
    specs: Map<Int, OverlaySlotSpec>,
    private val bus: SimpleEventBus<OverlayEvent> = SimpleEventBus(),
) : PlayerOverlay, EventSource<OverlayEvent> by bus {

    protected val grid = SlotGrid(specs)
    private val viewers = mutableSetOf<Player>()
    val activeViewers: Set<Player> get() = viewers

    private val updateLoop = OverlayUpdateLoop(this, grid, scheduler, ::repaint)
    private var packetListener: Disposable? = null

    final override var isDestroyed: Boolean = false
        private set

    init {
        // 把每个槽声明的点击/交互处理器，作为「按 index 过滤」的监听挂到覆盖层总线上。
        specs.forEach { (index, spec) ->
            spec.handlers.forEach { handler ->
                bus.subscribe(
                    EventListener<OverlaySlotEvent>(
                        handler.type,
                        null,
                        { if (index == this.index) handler.block(this) },
                        handler.priority,
                    )
                )
            }
        }
    }

    /** 子类在自身字段就绪后（构造末尾）调用：启动更新循环并注册包监听。 */
    protected fun startOverlay() {
        updateLoop.start()
        packetListener = registerPacketListener()
    }

    final override fun getItem(index: Int): ItemStack? =
        grid[index]?.item?.takeUnless { it.isEmptyStack() }

    final override fun setItem(index: Int, item: ItemStack?) {
        val slot = requireNotNull(grid[index]) { "slot $index is not declared on this overlay" }
      slot.item = item ?: item(Material.AIR)
        repaint(index)
    }

    protected fun publish(event: OverlayEvent) = bus.emit(event)

    protected fun addViewer(player: Player) {
        if (viewers.add(player)) publish(OverlayShowEvent(this, player))
    }

    protected fun removeViewer(player: Player): Boolean =
        viewers.remove(player).also { if (it) publish(OverlayHideEvent(this, player)) }

    /** 把某槽当前物品重绘给所有在线观察者（类型相关）。 */
    protected abstract fun repaint(index: Int)

    /** 注册本覆盖层的包监听器（类型相关：包 → 事件映射）。 */
    protected abstract fun registerPacketListener(): Disposable

    /** 观察者离开 / 覆盖层销毁时的清理（类型相关，如还原背包）。 */
    protected open fun onHide(player: Player) {}

    final override fun destroy() {
        if (isDestroyed) return
        activeViewers.toList().forEach { player ->
            removeViewer(player)
            onHide(player)
        }
        updateLoop.stop()
        packetListener?.dispose()
        bus.unsubscribeAll()
        viewers.clear()
        isDestroyed = true
    }
}
```

- [ ] **Step 5: 跑测试 + 全量 + stage**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿（含 `AbstractPlayerOverlayTest` 6 个）。
`git add platform-bukkit-impl/.../overlay/OverlayPacketExt.kt platform-bukkit-impl/.../overlay/AbstractPlayerOverlay.kt platform-bukkit-impl/.../overlay/AbstractPlayerOverlayTest.kt`

---

### Task 6：PacketPlayerOverlay（具体实现）+ PlayerOverlayBuilder

**Files:**
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/PacketPlayerOverlay.kt`
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/PlayerOverlayBuilder.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/PlayerOverlayBuilderTest.kt`

**Interfaces:**
- Consumes: Task 5 `AbstractPlayerOverlay`/`updateItem`/`updateCursorItem`；`PlayerOverlay`/`OverlayClickEvent`/`OverlayInteractEvent`/`PlayerOverlayScope`/`OverlaySlotScope`；`BukkitEasyLib.api.packetManager`；`sendPackets`/`ifTrue`/`getBukkitClickType`；PacketEvents wrappers。
- Produces:
  - `internal class PacketPlayerOverlay(taskScheduler, specs: Map<Int, OverlaySlotSpec>) : AbstractPlayerOverlay(...)`，实现 `show`/`hide`/`onHide`/`repaint`/`registerPacketListener`（windowId=0，收发包与 click/interact/drop 处理）。
  - `internal class PlayerOverlayBuilder(factory: (Map<Int, OverlaySlotSpec>) -> PlayerOverlay) : PlayerOverlayScope { fun build(): PlayerOverlay }`

> spec §6：封包渲染路径手动验证，单测只覆盖 builder 的声明透传（用捕获式 factory，不构造真实 `PacketPlayerOverlay`）。

- [ ] **Step 1: 写失败测试**（只测 builder）

创建 `PlayerOverlayBuilderTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.slot
import io.mockk.mockk
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlayerOverlayBuilderTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private lateinit var captured: Map<Int, OverlaySlotSpec>

    private fun build(block: com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope.() -> Unit) =
        PlayerOverlayBuilder { slots -> captured = slots; mockk<PlayerOverlay>() }.apply(block).build()

    @Test
    fun `slot 声明透传为 OverlaySlotSpec，含 handler`() {
        build {
            slot(0, Material.DIAMOND) { onClick { } }
            slot(5, Material.STONE)
        }
        assertEquals(setOf(0, 5), captured.keys)
        assertEquals(Material.DIAMOND, captured[0]!!.item.type)
        assertEquals(listOf<Class<*>>(OverlayClickEvent::class.java), captured[0]!!.handlers.map { it.type })
        assertTrue(captured[5]!!.handlers.isEmpty())
    }

    @Test
    fun `range slot 共享同一 spec 铺满区间`() {
        build { slot(1..3, Material.PAPER) }
        assertEquals(setOf(1, 2, 3), captured.keys)
    }

    @Test
    fun `越界 slot 抛 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            build { slot(46, Material.STONE) } // OVERLAY_SIZE=46，合法区间 [0,46)
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: 编译失败，`unresolved reference: PlayerOverlayBuilder`。

- [ ] **Step 3: 写 `PlayerOverlayBuilder.kt`**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.OverlaySlotScope
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** 收集 [PlayerOverlayScope] 声明为 `Map<Int, OverlaySlotSpec>`，交由 [factory] 造出覆盖层。 */
internal class PlayerOverlayBuilder(
    private val factory: (slots: Map<Int, OverlaySlotSpec>) -> PlayerOverlay,
) : PlayerOverlayScope {

    private val size: Int = PlayerOverlay.OVERLAY_SIZE
    private val slots = mutableMapOf<Int, OverlaySlotSpec>()

    override fun slot(
        index: Int,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (OverlaySlotScope.() -> Unit)?,
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size)" }
        slots[index] = buildSlot(item, metadata, block)
    }

    override fun slot(
        range: IntRange,
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (OverlaySlotScope.() -> Unit)?,
    ) {
        require(range.first >= 0 && range.last < size) { "slot must be in range [0, $size)" }
        val slot = buildSlot(item, metadata, block)
        range.forEach { slots[it] = slot }
    }

    private fun buildSlot(
        item: ItemStack,
        metadata: ItemMeta.() -> Unit,
        block: (OverlaySlotScope.() -> Unit)?,
    ): OverlaySlotSpec {
        return OverlaySlotBuilder()
            .apply { block?.invoke(this) }
            .build(item.also { item.itemMeta = item.itemMeta?.also(metadata) })
    }

    fun build(): PlayerOverlay = factory(slots)
}
```

- [ ] **Step 4: 写 `PacketPlayerOverlay.kt`**（迁自 `VirtualPlayerInventoryMenu`，事件改名）

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.OverlayClickEvent
import com.github.mayblock.easylib.api.bukkit.overlay.OverlayInteractEvent
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import com.github.mayblock.easylib.packetevents.packet.dsl.PacketScope
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import org.bukkit.entity.Player

/**
 * 基于数据包的玩家背包覆盖层（覆盖玩家自身窗口 windowId=0）：
 * 出站改写 WINDOW_ITEMS/SET_SLOT 用虚拟物品遮罩真实背包；入站拦截点击/挥动/使用/丢弃并派发覆盖层事件。
 */
internal class PacketPlayerOverlay(
    taskScheduler: TaskScheduler,
    specs: Map<Int, OverlaySlotSpec>,
) : AbstractPlayerOverlay(taskScheduler, specs) {

    private val windowId = 0

    init {
        startOverlay()
    }

    override fun show(player: Player) {
        check(!isDestroyed) { "this overlay is destroyed!" }
        player.sendPackets { forPlayer { syncOverlayItems() } }
        addViewer(player)
    }

    override fun hide(player: Player): Boolean {
        check(!isDestroyed) { "this overlay is destroyed!" }
        return removeViewer(player).ifTrue { player.updateInventory() }
    }

    override fun onHide(player: Player) {
        player.updateInventory()
    }

    override fun repaint(index: Int) {
        activeViewers.toList().forEach { player ->
            if (!player.isOnline) return@forEach
            player.sendPackets { forPlayer { updateItem(windowId, index, grid.packetItem(index)) } }
        }
    }

    override fun registerPacketListener(): Disposable =
        BukkitEasyLib.api.packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (player !in activeViewers) return
                e.isCancelled = when (e.packetType) {
                    PacketType.Play.Client.CLICK_WINDOW ->
                        handleClickWindow(player, WrapperPlayClientClickWindow(e))
                    PacketType.Play.Client.ANIMATION ->
                        handleInteract(player, OverlayInteractEvent.Action.LEFT_CLICK)
                    PacketType.Play.Client.USE_ITEM ->
                        handleInteract(player, OverlayInteractEvent.Action.RIGHT_CLICK)
                    PacketType.Play.Client.PLAYER_DIGGING -> {
                        val heldItemSlot = player.inventory.heldItemSlot + 36
                        handleDropItem(player, heldItemSlot, WrapperPlayClientPlayerDigging(e).action)
                    }
                    else -> false
                }
            }

            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (player !in activeViewers) return
                when (e.packetType) {
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        if (packet.windowId != 0) return
                        packet.items = (0 until PlayerOverlay.OVERLAY_SIZE).map { grid.packetItem(it) }
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId != 0) return
                        packet.item = grid.packetItem(packet.slot)
                    }
                }
            }
        })

    private fun handleClickWindow(player: Player, packet: WrapperPlayClientClickWindow): Boolean {
        if (packet.windowId != 0) return false
        if (packet.windowClickType == WrapperPlayClientClickWindow.WindowClickType.THROW) {
            val diggingAction = when (packet.button) {
                0 -> DiggingAction.DROP_ITEM
                1 -> DiggingAction.DROP_ITEM_STACK
                else -> throw UnsupportedOperationException()
            }
            return handleDropItem(player, packet.slot, diggingAction)
        }
        val clickType = packet.getBukkitClickType()
        val involvedSlots = packet.hashedSlots.keys
        involvedSlots.forEach { slot ->
            publish(OverlayClickEvent(this, slot, player, clickType))
        }
        player.sendPackets {
            forPlayer {
                updateCursorItem(null)
                involvedSlots.forEach { slot ->
                    updateItem(0, slot, grid.packetItem(slot))
                }
            }
        }
        return true
    }

    private fun handleInteract(player: Player, action: OverlayInteractEvent.Action): Boolean {
        val heldItemSlot = player.inventory.heldItemSlot + 36
        if (grid[heldItemSlot] == null) return false
        publish(OverlayInteractEvent(this, heldItemSlot, player, action))
        player.sendPackets { forPlayer { updateItem(0, heldItemSlot, grid.packetItem(heldItemSlot)) } }
        return true
    }

    private fun handleDropItem(player: Player, slot: Int, action: DiggingAction): Boolean {
        if (action != DiggingAction.DROP_ITEM && action != DiggingAction.DROP_ITEM_STACK) return false
        if (grid[slot] != null) {
            player.sendPackets { forPlayer { updateItem(0, slot, grid.packetItem(slot)) } }
        }
        return true
    }

    private fun PacketScope.PlayerPacketScope.syncOverlayItems() {
        containerItems(0, 0, grid.packetItems(PlayerOverlay.OVERLAY_SIZE))
    }
}
```

- [ ] **Step 5: 跑测试 + 全量 + stage**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿（含 `PlayerOverlayBuilderTest` 3 个；`PacketPlayerOverlay` 仅需编译通过）。
`git add platform-bukkit-impl/.../overlay/PacketPlayerOverlay.kt platform-bukkit-impl/.../overlay/PlayerOverlayBuilder.kt platform-bukkit-impl/.../overlay/PlayerOverlayBuilderTest.kt`

---

### Task 7：PlayerOverlayFactory + OverlayManager + 接入 BukkitEasyLibApi

**Files:**
- Create: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/overlay/PlayerOverlayFactory.kt`
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayManager.kt`
- Modify: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/BukkitEasyLibApi.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/BukkitEasyLib.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayManagerTest.kt`

**Interfaces:**
- Consumes: `PlayerOverlay`/`PlayerOverlayScope`；Task 6 `PlayerOverlayBuilder`/`PacketPlayerOverlay`；`TaskScheduler`；`java.io.Closeable`。
- Produces:
  - `interface PlayerOverlayFactory { fun create(builder: PlayerOverlayScope.() -> Unit): PlayerOverlay }`
  - `class OverlayManager(taskScheduler: TaskScheduler) : PlayerOverlayFactory, Closeable`（public，与 `MenuManager` 同因作为 `overlayFactory` 推断类型需被 `close()` 调用）。
  - `BukkitEasyLibApi` 新增 `val overlayFactory: PlayerOverlayFactory`（与 `menuFactory` 并列）。
- 先决检查：`grep -rn ": BukkitEasyLibApi" platform-bukkit-impl` 确认唯一实现是 `BukkitEasyLib`（否则每个实现都要补 `overlayFactory`）。

- [ ] **Step 1: 先决检查**

Run: `grep -rn "BukkitEasyLibApi" platform-bukkit-impl/src platform-bukkit-api/src`
Expected: 实现类仅 `BukkitEasyLib`；无测试替身实现该接口。若发现其它实现，补齐 `overlayFactory`。

- [ ] **Step 2: 写失败测试**

创建 `OverlayManagerTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import io.mockk.mockk
import kotlin.test.Test

class OverlayManagerTest {

    // 说明：create{} 会构造 PacketPlayerOverlay → 触发 PacketEvents/单例，单测环境不可跑，
    // 故此处仅冒烟覆盖生命周期（空态 close 幂等不抛）；覆盖层创建与渲染的正确性由手动验证兜底（spec §6）。
    @Test
    fun `空态 close 幂等且不抛`() {
        val mgr = OverlayManager(mockk<TaskScheduler>(relaxed = true))
        mgr.close()
        mgr.close()
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: 编译失败，`unresolved reference: OverlayManager`。

- [ ] **Step 4: 写 `PlayerOverlayFactory.kt`**

```kotlin
package com.github.mayblock.easylib.api.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope

interface PlayerOverlayFactory {
    /** 按 DSL 构建一个玩家背包覆盖层。 */
    fun create(builder: PlayerOverlayScope.() -> Unit): PlayerOverlay
}
```

- [ ] **Step 5: 写 `OverlayManager.kt`**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlayFactory
import com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import java.io.Closeable

/** 覆盖层工厂：创建并跟踪覆盖层，`close()` 时统一销毁（清理更新循环 + 包监听）。 */
class OverlayManager(
    private val taskScheduler: TaskScheduler,
) : PlayerOverlayFactory, Closeable {

    private val overlays = mutableListOf<PlayerOverlay>()

    override fun create(builder: PlayerOverlayScope.() -> Unit): PlayerOverlay =
        PlayerOverlayBuilder { slots -> PacketPlayerOverlay(taskScheduler, slots) }
            .apply(builder)
            .build()
            .also { overlays += it }

    override fun close() {
        overlays.forEach { it.destroy() }
        overlays.clear()
    }
}
```

- [ ] **Step 6: 改 `BukkitEasyLibApi.kt`**（新增 `overlayFactory`）

在 import 区加 `import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlayFactory`，接口体加一行：

```kotlin
interface BukkitEasyLibApi : EasyLibApi, Closeable {
    val dispatcher: BukkitDispatcher
    val promptApi: PromptApi
    val itemExtensionApi: ItemExtensionApi
    val menuFactory: MenuFactory
    val overlayFactory: PlayerOverlayFactory
}
```

- [ ] **Step 7: 改 `BukkitEasyLib.kt`**（wiring + close）

加 `import com.github.mayblock.easylib.impl.bukkit.overlay.OverlayManager`；在 `menuFactory` 下加：

```kotlin
    override val overlayFactory = OverlayManager(taskScheduler)
```

`close()` 追加 `overlayFactory.close()`：

```kotlin
    override fun close() {
        taskScheduler.cancelAllTasks()
        menuFactory.close()
        overlayFactory.close()
    }
```

- [ ] **Step 8: 跑测试 + 全量 + stage**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿（含 `OverlayManagerTest`）。此刻 `overlayFactory.create` 与旧 `menuFactory.createPlayerInventoryMenu` **并存**。
`git add platform-bukkit-api/.../overlay/PlayerOverlayFactory.kt platform-bukkit-api/.../BukkitEasyLibApi.kt platform-bukkit-impl/.../overlay/OverlayManager.kt platform-bukkit-impl/.../overlay/OverlayManagerTest.kt platform-bukkit-impl/.../BukkitEasyLib.kt`

---

### Task 8：切换调用方 + 收敛 MenuFactory + 重命名 VirtualMenuManager→MenuManager

**Files:**
- Modify: `platform-bukkit-impl/.../game/arena/service/SpectatorService.kt`
- Modify: `platform-bukkit-api/.../menu/MenuFactory.kt`（删 `createPlayerInventoryMenu`）
- Rename: `platform-bukkit-impl/.../menu/VirtualMenuManager.kt` → `MenuManager.kt`（类名同改，删 player 工厂方法与 import）
- Modify: `platform-bukkit-impl/.../BukkitEasyLib.kt`（`VirtualMenuManager` → `MenuManager`）
- Rename test: `platform-bukkit-impl/src/test/.../menu/VirtualMenuManagerChestTest.kt` → `MenuManagerChestTest.kt`（类名与 `manager()` 内构造同改）

**Interfaces:**
- Consumes: Task 7 `overlayFactory`（`BukkitEasyLib.api.overlayFactory`）；`PlayerOverlayScope`。
- Produces: `SpectatorService` 用 `overlayFactory.create(...)` + `show`/`hide`；`MenuFactory` 只剩 `createChestMenu`；`MenuManager`（原 `VirtualMenuManager`）。
- 先决检查：`grep -rn "createPlayerInventoryMenu\|virtualPlayerInventory\|SpectatorService(" platform-bukkit-impl/src` 确认调用面（应仅 `SpectatorService` 内 3 处 + 其构造点）。

- [ ] **Step 1: 迁移 `SpectatorService.kt`**

改 import：删 `...menu.type.player.dsl.PlayerMenuScope`，加 `com.github.mayblock.easylib.api.bukkit.overlay.dsl.PlayerOverlayScope`。改构造参数类型与字段与调用：

```kotlin
class SpectatorService<A : BukkitArena<out BukkitArenaPlayer, *>>(
    private val arena: A,
    playerInventory: (PlayerOverlayScope.() -> Unit)? = null,
) : Service {

    val playerOverlay = api.overlayFactory.create(playerInventory ?: {})
}
```

并把 `apply()`/`restore()` 内：

```kotlin
            playerOverlay.show(player)
```
```kotlin
            playerOverlay.hide(player)
```

（构造参数名保留 `playerInventory` 以免破坏具名实参调用；仅字段 `virtualPlayerInventory`→`playerOverlay`。若 Step 先决检查发现有 `.virtualPlayerInventory` 外部读取，一并改为 `.playerOverlay`。）

- [ ] **Step 2: 删 `MenuFactory.createPlayerInventoryMenu`**

删方法与两个 import（`PlayerInventoryMenu`、`PlayerMenuScope`），结果：

```kotlin
package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.PageableChestMenuScope

interface MenuFactory {

    /**
     * 创建箱子菜单。
     * @param hidePlayerInventory 打开菜单时是否用数据包屏蔽玩家背包物品（关闭菜单后自动恢复）。
     *   默认 true；声明了 `placeable` 槽位的菜单必须显式传 false，否则构建期报错。
     */
    fun createChestMenu(
        type: ChestMenuType,
        hidePlayerInventory: Boolean = true,
        builder: PageableChestMenuScope.() -> Unit,
    ): ChestMenu
}
```

- [ ] **Step 3: 重命名 `VirtualMenuManager` → `MenuManager`**

`git mv platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/VirtualMenuManager.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManager.kt`

改类名 `VirtualMenuManager` → `MenuManager`，删 `createPlayerInventoryMenu` override 及其 import（`PlayerInventoryMenu`、`PlayerMenuScope`、`VirtualPlayerInventoryMenu`、`PlayerMenuBuilder`）。结果：

```kotlin
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
    private val listener = MenuInteractionListener().also { Bukkit.getPluginManager().registerEvents(it, plugin) }

    override fun getActiveMenu(player: Player): Menu? = activeMenus[player]
    override fun hasActiveMenu(player: Player): Boolean = activeMenus.containsKey(player)
    override fun getViewers(menu: Menu): Set<Player> = activeMenus.filterValues { it === menu }.keys

    override fun createChestMenu(type: ChestMenuType, hidePlayerInventory: Boolean, builder: PageableChestMenuScope.() -> Unit): ChestMenu =
        register(
            PageableChestMenuBuilder(type) { title, slots ->
                RealChestMenu(taskScheduler, title, type, slots, hidePlayerInventory)
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
        HandlerList.unregisterAll(listener)
        menus.clear()
        activeMenus.clear()
    }
}
```

- [ ] **Step 4: 改 `BukkitEasyLib.kt`**（`VirtualMenuManager` → `MenuManager`）

import `...menu.VirtualMenuManager` → `...menu.MenuManager`；`override val menuFactory = VirtualMenuManager(taskScheduler, plugin)` → `MenuManager(taskScheduler, plugin)`。

- [ ] **Step 5: 重命名并改测试 `VirtualMenuManagerChestTest` → `MenuManagerChestTest`**

`git mv platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/VirtualMenuManagerChestTest.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManagerChestTest.kt`

改：`class VirtualMenuManagerChestTest` → `class MenuManagerChestTest`；`private fun manager() = VirtualMenuManager(...)` → `MenuManager(...)`。其余不变（三个 chest 测试仍应通过，证明改名未伤 chest）。

- [ ] **Step 6: 跑测试 + 全量 + stage**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿。此刻旧 player 类型已无引用（`VirtualPlayerInventoryMenu`/`PlayerMenuBuilder`/`PlayerInventoryMenu`/`InteractEvent`/`PlayerMenuDsl` 变为未引用），待 Task 9 删除。
`git add -A`（含 git mv 的改名）

---

### Task 9：删除旧 player 簇 + 收敛 MenuExt（原子大爆炸）

**Files:**
- Delete (api): `menu/type/player/PlayerInventoryMenu.kt`、`menu/type/player/InteractEvent.kt`、`menu/type/player/dsl/PlayerMenuDsl.kt`
- Delete (impl): `menu/type/player/VirtualPlayerInventoryMenu.kt`、`menu/type/player/builder/PlayerMenuBuilder.kt`、`menu/AbstractVirtualMenu.kt`、`menu/MenuUpdateLoop.kt`、`menu/SlotGrid.kt`、`menu/slot/LiveSlot.kt`、`menu/VirtualMenu.kt`
- Delete (test): `menu/AbstractVirtualMenuTest.kt`（其职责已由 `overlay/AbstractPlayerOverlayTest.kt` 承接，含 `isEmptyStack` 断言）
- Modify: `menu/MenuExt.kt`（删 `updateItem`/`updateCursorItem`，**保留** `isEmptyStack`）

- [ ] **Step 1: 先决检查（确认可安全删）**

Run: `grep -rn "VirtualPlayerInventoryMenu\|PlayerMenuBuilder\|AbstractVirtualMenu\|MenuUpdateLoop\|\.menu\.SlotGrid\|menu\.slot\.LiveSlot\|VirtualMenu\b\|PlayerInventoryMenu\|InteractEvent\|PlayerMenuScope\|PlayerMenuDsl" platform-bukkit-api/src platform-bukkit-impl/src`
Expected: 命中仅剩这些**待删文件自身**（互相引用）。若命中其它文件 → 停下修正。

- [ ] **Step 2: 删除旧文件**

```bash
git rm \
  platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/type/player/PlayerInventoryMenu.kt \
  platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/type/player/InteractEvent.kt \
  platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/type/player/dsl/PlayerMenuDsl.kt \
  platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/player/VirtualPlayerInventoryMenu.kt \
  platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/player/builder/PlayerMenuBuilder.kt \
  platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/AbstractVirtualMenu.kt \
  platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuUpdateLoop.kt \
  platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/SlotGrid.kt \
  platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/LiveSlot.kt \
  platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/VirtualMenu.kt \
  platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/AbstractVirtualMenuTest.kt
```

（若 `menu/type/player/` 及 `builder/` 目录变空，git 不跟踪空目录，无需额外处理。）

- [ ] **Step 3: 收敛 `MenuExt.kt`**（删 packet ext，留 isEmptyStack）

结果：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu

/** Bukkit 物品「空」判定：null、AIR 系或数量非正。chest 与 overlay 共用。 */
internal fun org.bukkit.inventory.ItemStack?.isEmptyStack(): Boolean =
    this == null || type.isAir || amount <= 0
```

- [ ] **Step 4: 全量测试**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿。旧 player 簇彻底移除，`Menu` 阵营只剩真实容器 ChestMenu。

- [ ] **Step 5: 全构建校验**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew build`
Expected: 所有模块编译打包成功（含 `platform-bukkit-api` 无残留引用）。

- [ ] **Step 6: stage**

`git add -A`

---

## Self-Review（对照 spec §5 逐项核验）

**Spec 覆盖：**
- §5.1 能力（仅展示+交互，去 movable/placeable）→ `OverlaySlotSpec`/`OverlaySlotBuilder`/`OverlaySlotScope` 无 movable/placeable/take/place；保留 46 格（`OVERLAY_SIZE`）、逐格虚拟显示（`SlotGrid`/`LiveSlot`）、`onUpdate` 异步（Task 4 `isAsync=true`）、click/interact（`OverlayClickEvent`/`OverlayInteractEvent`）、丢弃拦截（`handleDropItem`）、`show`/`hide`（Task 6）。✅
- §5.2 剥离与命名 → 新包 `api/impl.bukkit.overlay`；`PlayerOverlay`（不继承 Menu）/`PlayerOverlayFactory`/`PlayerOverlayScope`/`OverlayEvent`/`OverlayShow/Hide/Click/Interact`；迁移改名 `AbstractVirtualMenu→AbstractPlayerOverlay`、`MenuUpdateLoop→OverlayUpdateLoop`、`LiveSlot`/`SlotGrid` 入 overlay 包、packet 版 `MenuExt`→`OverlayPacketExt`；`OverlaySlotSpec`/`OverlaySlotBuilder`（无 movable/placeable）；入口 `BukkitEasyLibApi.overlayFactory`。✅（补充：spec §5.2 未列 `OverlayUpdateEvent`，但 `onUpdate` 必需其承载可变 `item`——已按 `SlotUpdateEvent` 对等补齐。）
- §5.3 MenuAPI 收敛 → 删 `createPlayerInventoryMenu`、`PlayerInventoryMenu`、`PlayerMenuScope`(在 `PlayerMenuDsl`)、`PlayerMenuBuilder`、`VirtualPlayerInventoryMenu`、`InteractEvent`/`InteractionType`（Task 8/9）。✅
- §5.4 调用方迁移 → `SpectatorService` 用 `overlayFactory.create` + `show`/`hide`（Task 8）。✅
- §7 破坏性迁移 + 不新增依赖 → 已确认无新依赖（`adventure-serializer-legacy` 是 Part A 引入）。✅
- 用户附加要求：`VirtualMenuManager` → `MenuManager`（Task 8）。✅

**判断项（超出 spec 字面、已在计划内定档，实现时如有异议可改）：**
1. `PlayerOverlay` 保留 `getItem/setItem`（承接「逐格虚拟显示」的运行时改写，能力对齐旧 `Menu`）。
2. 保留 `AbstractPlayerOverlay`+`PacketPlayerOverlay` 抽象/具体二分（对齐 spec §5.2 的迁移表，最小改动）。
3. `isEmptyStack` 留在 `menu.MenuExt`（chest 也用，属"非 packet"工具），overlay 跨包引用一处——仅 `updateItem/updateCursorItem` 移入 overlay。
4. `OverlaySlotScope` 单一 `@PlayerOverlayDsl` marker（覆盖两个 scope）。
5. `OverlayManager` public（与 `MenuManager` 一致，因作 `overlayFactory` 推断类型需 `close()`）。
6. `SpectatorService` 构造参数名保留 `playerInventory`（避免破坏具名实参），仅字段改 `playerOverlay`。

**Placeholder 扫描：** 无 TBD/TODO；每步含完整代码或精确改动。
**类型一致性：** `OverlaySlotEvent`/`OverlayHandler.type`/`EventListener<OverlaySlotEvent>` 贯穿一致；`OVERLAY_SIZE=46` 在接口/builder/packet 三处一致；`show/hide` 命名贯穿 API/impl/调用方一致。

## 建议实现顺序

Task 1 → 9 顺序推进（1–7 加法、8 切换、9 删除）。每个 Task 独立可测、结束测试绿。控制器在每个 Task 后 review + 提交（GPG 交互由控制器处理）。
