# MenuAPI 交互式 Slot 与背包屏蔽配置 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为箱子虚拟菜单增加 slot 级 `movable`/`placeable` 交互能力（真实物品由插件回调负责）与菜单级 `hidePlayerInventory` 配置，并修复隐藏范围 off-by-one。

**Architecture:** 纯决策核心（`ChestClickLogic`，无副作用、可单测）+ 副作用壳（`ChestClickEngine`：主线程串行调度、事件派发、经 `ChestClickRenderer` 接口发包）+ 现有声明链（`SlotSpec`/`SlotBuilder`/`ChestMenuBuilder`）透传两个布尔 flag。边界事件 `SlotTakeEvent`/`SlotPlaceEvent` 继承 `SlotClickEvent` 以复用菜单总线的 index 过滤注册机制。

**Tech Stack:** Kotlin JVM（工具链 25）、PacketEvents 2.12.2（已有 DSL）、JUnit Platform + MockK + MockBukkit（`platform-bukkit-impl` 测试依赖已配置，无需新增依赖）。

**Spec:** `docs/superpowers/specs/2026-07-02-menu-interactive-slots-design.md`（已批准）

## Global Constraints

- JVM 工具链 25；构建命令一律用 wrapper `./gradlew`（Windows Git Bash 下同样是 `./gradlew`）。
- API/实现分离：`platform-bukkit-api` 不得依赖任何 impl 模块；所有新实现类为 `internal`。
- 不新增第三方依赖（`gradle/libs.versions.toml` 不动）。
- 代码注释与 KDoc 用中文（跟随现有代码风格，见 `SlotSpec.kt`、`AbstractVirtualMenu.kt`）。
- 提交信息用 conventional commits（`feat:`/`fix:`/`test:`/`docs:`），结尾带 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 每个 Task 结束时构建必须是绿的（`./gradlew :platform-bukkit-impl:test` 通过）。
- 已核实的关键既有 API（直接使用，勿重造）：
  - `Event.Cancellable { var isCancelled: Boolean }`（common-api `event/Event.kt`）
  - `TaskScheduler.scheduleTask { onTick = {...} }` 默认 `Trigger.Once` + `isAsync=false`（主线程一次性任务）
  - `SimpleEventBus.emit` 按 `listener.type.isInstance(event)` 过滤，**监听器异常被 try/catch 吞掉并记日志、不外传**
  - `Player.sendPackets { forPlayer { ... } }`（`impl/bukkit/util/PacketExt.kt`）；`updateCursorItem(item?)`/`updateItem(windowId, slot, item)`（`impl/bukkit/menu/MenuExt.kt`）
  - `org.bukkit.inventory.ItemStack.fromBukkit(): packet ItemStack`（`impl/bukkit/util/PacketEventsExt.kt`）
  - `WrapperPlayClientClickWindow`: `.windowId` `.slot` `.button` `.windowClickType` `.hashedSlots`；`getBukkitClickType()`（`impl/bukkit/packet/extension/BukkitPacketExt.kt`）
  - MockBukkit 用法：`MockBukkit.mock()` / `MockBukkit.unmock()`（涉及 `ItemStack.isSimilar`/`itemMeta` 的测试必须先 mock server）

---

### Task 1: 边界事件 + SlotSpec/SlotBuilder 透传 flag 与 onTake/onPlace

**Files:**
- Create: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/slot/SlotTransferEvent.kt`
- Modify: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/slot/dsl/SlotScope.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotSpec.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotBuilder.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotBuilderTest.kt`

**Interfaces:**
- Consumes: `SlotClickEvent(menu, index, player)`（open class）、`Event.Cancellable`、`ClickHandler(priority, type, block)`、`Priority.DEFAULT`
- Produces（后续 Task 依赖）:
  - `class SlotTakeEvent(menu: Menu, index: Int, player: Player, val item: ItemStack, val targetSlot: Int, override var isCancelled: Boolean = false) : SlotClickEvent, Event.Cancellable`
  - `class SlotPlaceEvent(menu: Menu, index: Int, player: Player, val item: ItemStack, val sourceSlot: Int, override var isCancelled: Boolean = false) : SlotClickEvent, Event.Cancellable`
  - `SlotScope.onTake(priority = Priority.DEFAULT, block: SlotTakeEvent.() -> Unit)` / `SlotScope.onPlace(priority = Priority.DEFAULT, block: SlotPlaceEvent.() -> Unit)`
  - `SlotSpec(item, clickHandlers, updateRules, movable: Boolean = false, placeable: Boolean = false)`
  - `SlotBuilder.build(item: ItemStack, movable: Boolean = false, placeable: Boolean = false): SlotSpec`

- [ ] **Step 1: 写失败测试**

创建 `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotBuilderTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlotBuilderTest {

    private fun builder() = SlotBuilder(InventoryClickEvent::class.java)

    @Test
    fun `build 默认 movable 与 placeable 为 false`() {
        val spec = builder().build(ItemStack(Material.STONE))
        assertFalse(spec.movable)
        assertFalse(spec.placeable)
    }

    @Test
    fun `build 透传 movable 与 placeable`() {
        val spec = builder().build(ItemStack(Material.STONE), movable = true, placeable = true)
        assertTrue(spec.movable)
        assertTrue(spec.placeable)
    }

    @Test
    fun `onTake 与 onPlace 以对应事件类型收集为 ClickHandler`() {
        val spec = builder().apply {
            onTake { }
            onPlace { }
        }.build(ItemStack(Material.STONE))
        assertEquals(
            listOf<Class<*>>(SlotTakeEvent::class.java, SlotPlaceEvent::class.java),
            spec.clickHandlers.map { it.type },
        )
    }

    @Test
    fun `take 回调抛异常时事件被置为取消且异常继续外抛`() {
        val spec = builder().apply {
            onTake { throw IllegalStateException("boom") }
        }.build(ItemStack(Material.STONE))
        val event = SlotTakeEvent(mockk<Menu>(), 0, mockk<Player>(), ItemStack(Material.STONE), targetSlot = 0)
        assertFailsWith<IllegalStateException> { spec.clickHandlers.single().block(event) }
        assertTrue(event.isCancelled)
    }

    @Test
    fun `place 回调抛异常时事件被置为取消且异常继续外抛`() {
        val spec = builder().apply {
            onPlace { throw IllegalStateException("boom") }
        }.build(ItemStack(Material.STONE))
        val event = SlotPlaceEvent(mockk<Menu>(), 0, mockk<Player>(), ItemStack(Material.STONE), sourceSlot = 3)
        assertFailsWith<IllegalStateException> { spec.clickHandlers.single().block(event) }
        assertTrue(event.isCancelled)
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `SlotTakeEvent`/`SlotPlaceEvent` 未定义、`build` 无 movable/placeable 参数、`onTake`/`onPlace` 不存在。

- [ ] **Step 3: 实现**

创建 `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/slot/SlotTransferEvent.kt`：

```kotlin
package com.github.mayblock.easylib.api.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.event.Event
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 玩家把菜单槽位中的物品取出到自己背包时派发（可取消）。
 *
 * 引擎只维护虚拟层：未取消时，**真实物品的给予由订阅方负责**（如 `player.inventory.addItem(item)`），
 * 引擎随后会重同步客户端视觉。取消则虚拟光标维持原状。
 *
 * @param index 物品来源的菜单槽位
 * @param item 被取走物品的副本
 * @param targetSlot 玩家点击的目标真实背包槽位（Bukkit `PlayerInventory` 语义 0-35），供回调精确放置
 */
class SlotTakeEvent(
    menu: Menu,
    index: Int,
    player: Player,
    val item: ItemStack,
    val targetSlot: Int,
    override var isCancelled: Boolean = false,
) : SlotClickEvent(menu, index, player), Event.Cancellable

/**
 * 玩家把自己背包的物品放入菜单槽位时派发（可取消）。
 *
 * 引擎只维护虚拟层：未取消时，**真实物品的扣除由订阅方负责**（按 [item] 的数量从 [sourceSlot] 扣除），
 * 引擎随后提交虚拟槽位并调用 `player.updateInventory()` 渲染扣除结果。取消则回滚重刷。
 *
 * @param index 放入的目标菜单槽位
 * @param item 待放入物品的副本（右键放置时数量可能小于光标持有量）
 * @param sourceSlot 物品来源的真实背包槽位（Bukkit `PlayerInventory` 语义 0-35）
 */
class SlotPlaceEvent(
    menu: Menu,
    index: Int,
    player: Player,
    val item: ItemStack,
    val sourceSlot: Int,
    override var isCancelled: Boolean = false,
) : SlotClickEvent(menu, index, player), Event.Cancellable
```

修改 `SlotScope.kt` 为：

```kotlin
package com.github.mayblock.easylib.api.bukkit.menu.slot.dsl

import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority

@DslMarker
annotation class SlotDsl

@SlotDsl
interface SlotScope<out C : SlotClickEvent> {
    fun onClick(priority: Priority = Priority.DEFAULT, block: C.() -> Unit)
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: SlotUpdateEvent.() -> Unit)

    /**
     * 物品被从本槽位取出（见 [SlotTakeEvent] 的回调职责契约）。
     * v1 仅箱子菜单会派发；玩家背包菜单声明后不会触发。
     */
    fun onTake(priority: Priority = Priority.DEFAULT, block: SlotTakeEvent.() -> Unit)

    /**
     * 玩家物品被放入本槽位（见 [SlotPlaceEvent] 的回调职责契约）。
     * v1 仅箱子菜单会派发；玩家背包菜单声明后不会触发。
     */
    fun onPlace(priority: Priority = Priority.DEFAULT, block: SlotPlaceEvent.() -> Unit)
}
```

修改 `SlotSpec.kt` 中 `SlotSpec` 类为（`ClickHandler`/`UpdateRule` 不动）：

```kotlin
/**
 * 槽的「不可变声明」：用户通过 DSL 声明了什么（初始物品 + 点击处理器 + 更新规则 + 交互能力），
 * 零运行态。由 [SlotBuilder] 产出，运行期对应物是 [LiveSlot]。
 *
 * @param movable 槽中物品可被玩家拿起（真实给予由 take 回调负责）
 * @param placeable 玩家可把自己背包的物品放入本槽（真实扣除由 place 回调负责）
 */
internal class SlotSpec(
    val item: ItemStack,
    val clickHandlers: List<ClickHandler>,
    val updateRules: List<UpdateRule>,
    val movable: Boolean = false,
    val placeable: Boolean = false,
)
```

修改 `SlotBuilder.kt` 为：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.slot

import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.dsl.SlotScope
import com.github.mayblock.easylib.api.event.Event
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Priority
import org.bukkit.inventory.ItemStack

/**
 * 实现 api 的 [SlotScope]，把用户声明收集成不可变的 [SlotSpec]。纯声明、无运行态、无总线。
 *
 * 点击处理器以 [clickType] 标注事件类型——它会被注册到菜单总线并按 `type.isInstance` 过滤，
 * 因此把 `C.()->Unit` 当作 `SlotClickEvent.()->Unit` 存储是安全的。
 */
internal class SlotBuilder<C : SlotClickEvent>(
    private val clickType: Class<C>,
) : SlotScope<C> {

    private val clicks = mutableListOf<ClickHandler>()
    private val updates = mutableListOf<UpdateRule>()

    override fun onClick(priority: Priority, block: C.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        clicks += ClickHandler(priority, clickType, block as SlotClickEvent.() -> Unit)
    }

    override fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority, block: SlotUpdateEvent.() -> Unit) {
        updates += UpdateRule(trigger, block)
    }

    override fun onTake(priority: Priority, block: SlotTakeEvent.() -> Unit) {
        clicks += ClickHandler(priority, SlotTakeEvent::class.java, cancellingOnException(block))
    }

    override fun onPlace(priority: Priority, block: SlotPlaceEvent.() -> Unit) {
        clicks += ClickHandler(priority, SlotPlaceEvent::class.java, cancellingOnException(block))
    }

    fun build(item: ItemStack, movable: Boolean = false, placeable: Boolean = false): SlotSpec =
        SlotSpec(item, clicks.toList(), updates.toList(), movable, placeable)

    /**
     * 事件总线会吞掉监听器异常（记日志后继续）。take/place 回调承担「真实物品给予/扣除」职责，
     * 半途异常必须视为取消，否则会出现「虚拟层已提交、真实操作未完成」的不一致。
     * 这里先置取消再重新抛出，日志仍由总线负责。
     */
    private fun <E> cancellingOnException(block: E.() -> Unit): SlotClickEvent.() -> Unit
        where E : SlotClickEvent, E : Event.Cancellable = {
        @Suppress("UNCHECKED_CAST")
        val event = this as E
        try {
            block(event)
        } catch (e: Exception) {
            event.isCancelled = true
            throw e
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilderTest"`
Expected: PASS（5 个测试）

- [ ] **Step 5: 提交**

```bash
git add platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/slot/SlotTransferEvent.kt platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/slot/dsl/SlotScope.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotSpec.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotBuilder.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/SlotBuilderTest.kt
git commit -m "feat(menu): add SlotTakeEvent/SlotPlaceEvent and movable/placeable slot declaration

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Menu.getItem/setItem 与事件派发验证

**Files:**
- Modify: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/Menu.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuExt.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/AbstractVirtualMenu.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/LiveSlot.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/AbstractVirtualMenuTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `SlotTakeEvent`/`SlotPlaceEvent`/`SlotBuilder.build(item, movable, placeable)`；`SlotGrid[index]: LiveSlot?`；`AbstractVirtualMenu.publish/repaint`；`VirtualMenu.windowId`
- Produces:
  - `Menu.getItem(index: Int): ItemStack?`（未声明槽位或 AIR/数量≤0 → null）
  - `Menu.setItem(index: Int, item: ItemStack?)`（未声明槽位 → `IllegalArgumentException`；null → AIR；触发 repaint）
  - `internal fun org.bukkit.inventory.ItemStack?.isEmptyStack(): Boolean`（`MenuExt.kt`，后续 Task 3/6 使用）
  - `LiveSlot.movable: Boolean` / `LiveSlot.placeable: Boolean`

- [ ] **Step 1: 写失败测试**

创建 `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/AbstractVirtualMenuTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 不调用 startMenu 的最小实现：不触碰 PacketEvents/调度器，纯测 grid 访问与总线派发。 */
private class TestMenu(
    specs: Map<Int, SlotSpec>,
) : AbstractVirtualMenu(mockk<TaskScheduler>(relaxed = true), specs) {
    override val windowId = 1
    val repaints = mutableListOf<Int>()
    override fun repaint(index: Int) { repaints += index }
    override fun registerPacketListener(): Disposable = Disposable { }
    override fun open(player: Player) {}
    fun emit(event: MenuEvent) = publish(event)
}

class AbstractVirtualMenuTest {

    private fun specOf(item: ItemStack, block: SlotBuilder<InventoryClickEvent>.() -> Unit = {}): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java).apply(block).build(item)

    @Test
    fun `getItem 返回声明槽位的当前物品`() {
        val menu = TestMenu(mapOf(3 to specOf(ItemStack(Material.STONE, 5))))
        assertEquals(Material.STONE, menu.getItem(3)!!.type)
        assertEquals(5, menu.getItem(3)!!.amount)
    }

    @Test
    fun `getItem 对未声明槽位与 AIR 槽位返回 null`() {
        val menu = TestMenu(mapOf(3 to specOf(ItemStack(Material.AIR))))
        assertNull(menu.getItem(3))
        assertNull(menu.getItem(4))
    }

    @Test
    fun `setItem 写入声明槽位并触发 repaint，null 等价 AIR`() {
        val menu = TestMenu(mapOf(3 to specOf(ItemStack(Material.AIR))))
        menu.setItem(3, ItemStack(Material.DIAMOND, 2))
        assertEquals(Material.DIAMOND, menu.getItem(3)!!.type)
        menu.setItem(3, null)
        assertNull(menu.getItem(3))
        assertEquals(listOf(3, 3), menu.repaints)
    }

    @Test
    fun `setItem 对未声明槽位抛 IllegalArgumentException`() {
        val menu = TestMenu(mapOf(3 to specOf(ItemStack(Material.AIR))))
        assertFailsWith<IllegalArgumentException> { menu.setItem(4, ItemStack(Material.DIAMOND)) }
    }

    @Test
    fun `onTake 声明经总线按 index 过滤派发`() {
        var takeCalls = 0
        val menu = TestMenu(
            mapOf(3 to specOf(ItemStack(Material.STONE)) { onTake { takeCalls++ } }),
        )
        val player = mockk<Player>(relaxed = true)
        menu.emit(SlotTakeEvent(menu, 3, player, ItemStack(Material.STONE), targetSlot = 0))
        menu.emit(SlotTakeEvent(menu, 4, player, ItemStack(Material.STONE), targetSlot = 0))
        assertEquals(1, takeCalls)
    }

    @Test
    fun `isEmptyStack 判定 null、AIR 与非空`() {
        assertTrue((null as ItemStack?).isEmptyStack())
        assertTrue(ItemStack(Material.AIR).isEmptyStack())
        assertTrue(!ItemStack(Material.STONE).isEmptyStack())
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `getItem`/`setItem`/`isEmptyStack` 未定义。

- [ ] **Step 3: 实现**

修改 `Menu.kt` 为：

```kotlin
package com.github.mayblock.easylib.api.bukkit.menu

import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.util.Destroyable
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 菜单对外只暴露「订阅侧」事件源（[EventSource]）——可监听 [MenuEvent]（开/关、槽点击等），
 * 但 `emit` 被实现内部持有，外部无法伪造事件。
 *
 * 本接口不支持库外部实现，实例只应经 [MenuFactory] 创建。
 */
interface Menu : Destroyable, EventSource<MenuEvent> {
    fun open(player: Player)

    /** 某声明槽位的当前虚拟物品；未声明的槽位或当前为空（AIR/数量≤0）返回 null。 */
    fun getItem(index: Int): ItemStack?

    /**
     * 改写某声明槽位的虚拟物品并重绘给所有观看者；`null` 等价于清空（AIR）。
     * @throws IllegalArgumentException 槽位未在构建时声明
     */
    fun setItem(index: Int, item: ItemStack?)
}
```

在 `MenuExt.kt` 末尾追加：

```kotlin
/** Bukkit 物品「空」判定：null、AIR 系或数量非正。 */
internal fun org.bukkit.inventory.ItemStack?.isEmptyStack(): Boolean =
    this == null || type.isAir || amount <= 0
```

在 `LiveSlot.kt` 的 `clickHandlers`/`updateRules` 属性旁追加：

```kotlin
    val movable: Boolean get() = spec.movable
    val placeable: Boolean get() = spec.placeable
```

在 `AbstractVirtualMenu.kt` 中 `publish` 方法之前插入（并在文件头 import `org.bukkit.Material` 与 `org.bukkit.inventory.ItemStack`）：

```kotlin
    final override fun getItem(index: Int): ItemStack? =
        grid[index]?.item?.takeUnless { it.isEmptyStack() }

    final override fun setItem(index: Int, item: ItemStack?) {
        val slot = requireNotNull(grid[index]) { "slot $index is not declared on this menu" }
        slot.item = item ?: ItemStack(Material.AIR)
        repaint(index)
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.AbstractVirtualMenuTest"`
Expected: PASS（6 个测试）。同时跑 `./gradlew :platform-bukkit-impl:compileKotlin` 确认全模块仍编译（`VirtualPlayerInventoryMenu` 经基类自动获得两个新方法）。

- [ ] **Step 5: 提交**

```bash
git add platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/Menu.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuExt.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/AbstractVirtualMenu.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/slot/LiveSlot.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/AbstractVirtualMenuTest.kt
git commit -m "feat(menu): expose Menu.getItem/setItem backed by the slot grid

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 纯决策核心 ChestClickLogic

**Files:**
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickLogic.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickLogicTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `isEmptyStack()`（`impl.bukkit.menu` 包）；Task 1 的 `SlotSpec.placeable`
- Produces（Task 5/6 依赖，签名务必一致）:
  - `internal sealed class CursorOrigin { class MenuSlot(val index: Int); class PlayerInventory(val windowSlot: Int) }`
  - `internal class VirtualCursor(val item: ItemStack, val origin: CursorOrigin)`
  - `internal class SlotView(val item: ItemStack?, val movable: Boolean, val placeable: Boolean)`
  - `internal sealed class ClickDecision { Deny; PickupFromMenu(slot, amount); PickupFromInventory(windowSlot); PlaceInMenu(slot, amount, fromInventory); SwapWithMenu(slot); PutBackToInventory; DropToInventory(windowSlot) }`
  - `internal class ChestClickLogic(menuSize: Int, hidePlayerInventory: Boolean, hasPlaceableSlot: Boolean)` 的 `fun decide(windowSlot: Int, rightClick: Boolean, cursor: VirtualCursor?, menuSlot: SlotView?, bottomItem: ItemStack?): ClickDecision`
  - `internal fun playerInventoryWindowSlots(menuSize: Int): IntRange`
  - `internal fun chestWindowSlotToBukkit(windowSlot: Int, menuSize: Int): Int`
  - `internal fun requirePlaceableVisible(hidePlayerInventory: Boolean, specs: Map<Int, SlotSpec>)`

- [ ] **Step 1: 写失败测试**

创建 `ChestClickLogicTest.kt`（涉及 `isSimilar`/`maxStackSize`，需 MockBukkit）：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChestClickLogicTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private val menuSize = 27
    private fun logic(hide: Boolean = false, hasPlaceable: Boolean = true) =
        ChestClickLogic(menuSize, hide, hasPlaceable)

    private fun view(item: ItemStack?, movable: Boolean = false, placeable: Boolean = false) =
        SlotView(item, movable, placeable)

    private fun menuCursor(item: ItemStack, origin: Int) = VirtualCursor(item, CursorOrigin.MenuSlot(origin))
    private fun invCursor(item: ItemStack, ws: Int) = VirtualCursor(item, CursorOrigin.PlayerInventory(ws))

    // ── 空光标 · 菜单区 ────────────────────────────────────────────

    @Test
    fun `左键 movable 槽位拿起全部`() {
        val d = logic().decide(5, false, null, view(ItemStack(Material.STONE, 5), movable = true), null)
        assertIs<ClickDecision.PickupFromMenu>(d)
        assertEquals(5, d.slot); assertEquals(5, d.amount)
    }

    @Test
    fun `右键 movable 槽位拿起向上取整的一半`() {
        val d = logic().decide(5, true, null, view(ItemStack(Material.STONE, 5), movable = true), null)
        assertIs<ClickDecision.PickupFromMenu>(d)
        assertEquals(3, d.amount)
    }

    @Test
    fun `非 movable、空物品、未声明槽位一律拒绝`() {
        assertIs<ClickDecision.Deny>(logic().decide(5, false, null, view(ItemStack(Material.STONE), movable = false), null))
        assertIs<ClickDecision.Deny>(logic().decide(5, false, null, view(ItemStack(Material.AIR), movable = true), null))
        assertIs<ClickDecision.Deny>(logic().decide(5, false, null, null, null))
    }

    // ── 空光标 · 背包区 ────────────────────────────────────────────

    @Test
    fun `背包区拿起需要 hide=false、存在 placeable 槽且槽位有物品`() {
        assertIs<ClickDecision.PickupFromInventory>(logic().decide(30, false, null, null, ItemStack(Material.EMERALD)))
        assertIs<ClickDecision.Deny>(logic(hide = true, hasPlaceable = false).decide(30, false, null, null, ItemStack(Material.EMERALD)))
        assertIs<ClickDecision.Deny>(logic(hasPlaceable = false).decide(30, false, null, null, ItemStack(Material.EMERALD)))
        assertIs<ClickDecision.Deny>(logic().decide(30, false, null, null, null))
    }

    // ── 菜单源光标 · 菜单区 ─────────────────────────────────────────

    @Test
    fun `菜单源光标放入空 placeable 槽位（左键全放、右键放一）`() {
        val cursor = menuCursor(ItemStack(Material.STONE, 4), origin = 2)
        val left = logic().decide(5, false, cursor, view(null, placeable = true), null)
        assertIs<ClickDecision.PlaceInMenu>(left)
        assertEquals(4, left.amount); assertTrue(!left.fromInventory)
        val right = logic().decide(5, true, cursor, view(null, placeable = true), null)
        assertIs<ClickDecision.PlaceInMenu>(right)
        assertEquals(1, right.amount)
    }

    @Test
    fun `放回来源槽位不要求 placeable`() {
        val cursor = menuCursor(ItemStack(Material.STONE, 4), origin = 5)
        val d = logic().decide(5, false, cursor, view(null, movable = true, placeable = false), null)
        assertIs<ClickDecision.PlaceInMenu>(d)
    }

    @Test
    fun `非 placeable 且非来源槽位拒绝放置`() {
        val cursor = menuCursor(ItemStack(Material.STONE, 4), origin = 2)
        assertIs<ClickDecision.Deny>(logic().decide(5, false, cursor, view(null, placeable = false), null))
    }

    @Test
    fun `同类堆叠受 maxStackSize 限制，满栈拒绝`() {
        val cursor = menuCursor(ItemStack(Material.STONE, 10), origin = 2)
        val d = logic().decide(5, false, cursor, view(ItemStack(Material.STONE, 60), placeable = true), null)
        assertIs<ClickDecision.PlaceInMenu>(d)
        assertEquals(4, d.amount) // 64 - 60
        assertIs<ClickDecision.Deny>(
            logic().decide(5, false, cursor, view(ItemStack(Material.STONE, 64), placeable = true), null)
        )
    }

    @Test
    fun `异类交换需要目标同时 movable 与 placeable，且真实源交换一律拒绝`() {
        val menuOrigin = menuCursor(ItemStack(Material.STONE, 1), origin = 2)
        val target = view(ItemStack(Material.DIRT, 1), movable = true, placeable = true)
        assertIs<ClickDecision.SwapWithMenu>(logic().decide(5, false, menuOrigin, target, null))
        assertIs<ClickDecision.Deny>(
            logic().decide(5, false, menuOrigin, view(ItemStack(Material.DIRT, 1), movable = true, placeable = false), null)
        )
        val invOrigin = invCursor(ItemStack(Material.STONE, 1), ws = 30)
        assertIs<ClickDecision.Deny>(logic().decide(5, false, invOrigin, target, null))
    }

    // ── 背包源光标 · 菜单区 ─────────────────────────────────────────

    @Test
    fun `背包源光标放入空 placeable 槽位标记 fromInventory`() {
        val d = logic().decide(5, false, invCursor(ItemStack(Material.EMERALD, 2), 30), view(null, placeable = true), null)
        assertIs<ClickDecision.PlaceInMenu>(d)
        assertTrue(d.fromInventory)
    }

    // ── 光标 · 背包区 ──────────────────────────────────────────────

    @Test
    fun `菜单源光标点背包区任意槽位为取出`() {
        val d = logic().decide(30, false, menuCursor(ItemStack(Material.STONE, 1), 2), null, null)
        assertIs<ClickDecision.DropToInventory>(d)
        assertEquals(30, d.windowSlot)
    }

    @Test
    fun `背包源光标仅可放回原槽位，其它拒绝`() {
        val cursor = invCursor(ItemStack(Material.EMERALD, 1), ws = 30)
        assertIs<ClickDecision.PutBackToInventory>(logic().decide(30, false, cursor, null, null))
        assertIs<ClickDecision.Deny>(logic().decide(31, false, cursor, null, null))
    }

    // ── 辅助函数 ──────────────────────────────────────────────────

    @Test
    fun `playerInventoryWindowSlots 覆盖 36 个槽且不含菜单区`() {
        assertEquals(27..62, playerInventoryWindowSlots(27))
    }

    @Test
    fun `chestWindowSlotToBukkit 主背包与热键栏换算`() {
        assertEquals(9, chestWindowSlotToBukkit(27, 27))
        assertEquals(35, chestWindowSlotToBukkit(53, 27))
        assertEquals(0, chestWindowSlotToBukkit(54, 27))
        assertEquals(8, chestWindowSlotToBukkit(62, 27))
    }

    @Test
    fun `requirePlaceableVisible 在 hide 且存在 placeable 时报错`() {
        val placeableSpec = SlotBuilder(InventoryClickEvent::class.java)
            .build(ItemStack(Material.AIR), placeable = true)
        val plainSpec = SlotBuilder(InventoryClickEvent::class.java).build(ItemStack(Material.STONE))
        assertFailsWith<IllegalArgumentException> {
            requirePlaceableVisible(hidePlayerInventory = true, specs = mapOf(0 to placeableSpec))
        }
        requirePlaceableVisible(hidePlayerInventory = true, specs = mapOf(0 to plainSpec))
        requirePlaceableVisible(hidePlayerInventory = false, specs = mapOf(0 to placeableSpec))
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `ChestClickLogic` 等类型未定义。

- [ ] **Step 3: 实现**

创建 `ChestClickLogic.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import org.bukkit.inventory.ItemStack

/** 虚拟光标物品的来源。 */
internal sealed class CursorOrigin {
    /** 从菜单槽位拿起。 */
    class MenuSlot(val index: Int) : CursorOrigin()

    /** 从玩家真实背包拿起（[windowSlot] 为箱子窗口坐标；真实物品从未离开背包）。 */
    class PlayerInventory(val windowSlot: Int) : CursorOrigin()
}

/** per-player 虚拟光标：物品副本 + 来源。 */
internal class VirtualCursor(val item: ItemStack, val origin: CursorOrigin)

/** 决策所需的菜单槽位视图（与运行态解耦，便于纯单测）。 */
internal class SlotView(val item: ItemStack?, val movable: Boolean, val placeable: Boolean)

/** PICKUP 点击的决策结果；副作用由 ChestClickEngine 执行。 */
internal sealed class ClickDecision {
    object Deny : ClickDecision()

    /** 从菜单槽位拿起 [amount] 个到虚拟光标（纯虚拟）。 */
    class PickupFromMenu(val slot: Int, val amount: Int) : ClickDecision()

    /** 从玩家真实背包槽位「视觉拿起」（不动真实背包，仅记录来源）。 */
    class PickupFromInventory(val windowSlot: Int) : ClickDecision()

    /** 放入菜单槽位；[fromInventory] 时须先派发 SlotPlaceEvent。 */
    class PlaceInMenu(val slot: Int, val amount: Int, val fromInventory: Boolean) : ClickDecision()

    /** 菜单源光标与槽位异类物品交换（纯虚拟）。 */
    class SwapWithMenu(val slot: Int) : ClickDecision()

    /** 背包源光标放回原真实槽位（视觉还原）。 */
    object PutBackToInventory : ClickDecision()

    /** 菜单源光标落入背包区 → 派发 SlotTakeEvent。 */
    class DropToInventory(val windowSlot: Int) : ClickDecision()
}

/** 箱子窗口中玩家背包区的窗口槽位范围（27 主背包 + 9 热键栏，共 36）。 */
internal fun playerInventoryWindowSlots(menuSize: Int): IntRange = menuSize until menuSize + 36

/** 窗口槽位 → Bukkit `PlayerInventory` 槽位（窗口先排主背包 9-35，再排热键栏 0-8）。 */
internal fun chestWindowSlotToBukkit(windowSlot: Int, menuSize: Int): Int {
    val rel = windowSlot - menuSize
    return if (rel < 27) rel + 9 else rel - 27
}

/** placeable 槽位要求背包可见：背包被屏蔽时玩家永远拿不起自己的物品，属静态矛盾，构建期即报错。 */
internal fun requirePlaceableVisible(hidePlayerInventory: Boolean, specs: Map<Int, SlotSpec>) {
    require(!(hidePlayerInventory && specs.values.any { it.placeable })) {
        "placeable slots require hidePlayerInventory = false: " +
            "with the player inventory hidden, players can never pick up their own items to place"
    }
}

/**
 * 点击状态机的纯决策核心：只处理 PICKUP（左/右键）模式，无副作用。
 * 输入是点击参数 + 光标 + 槽位视图快照，输出 [ClickDecision]，由引擎执行副作用。
 */
internal class ChestClickLogic(
    private val menuSize: Int,
    private val hidePlayerInventory: Boolean,
    private val hasPlaceableSlot: Boolean,
) {

    /**
     * @param menuSlot 菜单区槽位视图；点击背包区或未声明槽位时为 null
     * @param bottomItem 点击背包区时该真实槽位的物品；点击菜单区时为 null
     */
    fun decide(
        windowSlot: Int,
        rightClick: Boolean,
        cursor: VirtualCursor?,
        menuSlot: SlotView?,
        bottomItem: ItemStack?,
    ): ClickDecision {
        val inMenuArea = windowSlot < menuSize
        return when {
            cursor == null && inMenuArea -> decideEmptyCursorMenu(windowSlot, rightClick, menuSlot)
            cursor == null -> decideEmptyCursorBottom(windowSlot, bottomItem)
            inMenuArea -> decideHoldingMenu(windowSlot, rightClick, cursor, menuSlot)
            else -> decideHoldingBottom(windowSlot, cursor)
        }
    }

    private fun decideEmptyCursorMenu(slot: Int, right: Boolean, view: SlotView?): ClickDecision {
        if (view == null || !view.movable || view.item.isEmptyStack()) return ClickDecision.Deny
        val amount = view.item!!.amount
        return ClickDecision.PickupFromMenu(slot, if (right) amount - amount / 2 else amount)
    }

    private fun decideEmptyCursorBottom(windowSlot: Int, bottomItem: ItemStack?): ClickDecision {
        if (hidePlayerInventory || !hasPlaceableSlot || bottomItem.isEmptyStack()) return ClickDecision.Deny
        return ClickDecision.PickupFromInventory(windowSlot)
    }

    private fun decideHoldingMenu(slot: Int, right: Boolean, cursor: VirtualCursor, view: SlotView?): ClickDecision {
        if (view == null) return ClickDecision.Deny
        val origin = cursor.origin
        val isPutBack = origin is CursorOrigin.MenuSlot && origin.index == slot
        val fromInventory = origin is CursorOrigin.PlayerInventory
        val target = view.item
        return when {
            target.isEmptyStack() -> {
                if (!view.placeable && !isPutBack) return ClickDecision.Deny
                ClickDecision.PlaceInMenu(slot, if (right) 1 else cursor.item.amount, fromInventory)
            }
            target!!.isSimilar(cursor.item) -> {
                if (!view.placeable && !isPutBack) return ClickDecision.Deny
                val space = target.maxStackSize - target.amount
                if (space <= 0) return ClickDecision.Deny
                ClickDecision.PlaceInMenu(slot, minOf(space, if (right) 1 else cursor.item.amount), fromInventory)
            }
            else -> {
                // 异类交换：真实源不支持（v1）；菜单源要求目标可取又可放
                if (fromInventory || !view.movable || !view.placeable) return ClickDecision.Deny
                ClickDecision.SwapWithMenu(slot)
            }
        }
    }

    private fun decideHoldingBottom(windowSlot: Int, cursor: VirtualCursor): ClickDecision =
        when (val origin = cursor.origin) {
            is CursorOrigin.MenuSlot -> ClickDecision.DropToInventory(windowSlot)
            is CursorOrigin.PlayerInventory ->
                if (origin.windowSlot == windowSlot) ClickDecision.PutBackToInventory else ClickDecision.Deny
        }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.ChestClickLogicTest"`
Expected: PASS（15 个测试）

- [ ] **Step 5: 提交**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickLogic.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickLogicTest.kt
git commit -m "feat(menu): add pure click decision core for interactive chest slots

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: ChestMenuScope.slot 增加 movable/placeable 参数

**Files:**
- Modify: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/type/chest/dsl/ChestMenuDsl.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/builder/ChestMenuBuilder.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/builder/ChestMenuBuilderTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `SlotBuilder.build(item, movable, placeable)`、`SlotSpec.movable/placeable`
- Produces:
  - `ChestMenuScope.slot(index: Int, item: ItemStack, movable: Boolean = false, placeable: Boolean = false, metadata: ItemMeta.() -> Unit = {}, block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null)` 与 range 重载、两个 Material 扩展同构
  - 兼容性注意：按位置传 `metadata` 的旧调用编译期报错（具名调用与 trailing lambda 不受影响）——预期行为，见 spec §8

- [ ] **Step 1: 写失败测试**

创建 `ChestMenuBuilderTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChestMenuBuilderTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun buildSpecs(block: ChestMenuBuilder.() -> Unit): Map<Int, SlotSpec> {
        var captured: Map<Int, SlotSpec> = emptyMap()
        ChestMenuBuilder(ChestMenuType.GENERIC_9X3, Component.text("t")) { _, slots ->
            captured = slots
            mockk<ChestMenu>(relaxed = true)
        }.apply(block).build()
        return captured
    }

    @Test
    fun `slot 透传 movable 与 placeable 到 SlotSpec`() {
        val specs = buildSpecs {
            slot(0, ItemStack(Material.STONE))
            slot(1, ItemStack(Material.DIAMOND), movable = true)
            slot(2, ItemStack(Material.AIR), placeable = true)
        }
        assertFalse(specs.getValue(0).movable); assertFalse(specs.getValue(0).placeable)
        assertTrue(specs.getValue(1).movable); assertFalse(specs.getValue(1).placeable)
        assertFalse(specs.getValue(2).movable); assertTrue(specs.getValue(2).placeable)
    }

    @Test
    fun `range 重载对每个槽位透传 flag`() {
        val specs = buildSpecs { slot(3..5, ItemStack(Material.STONE), movable = true) }
        assertEquals(setOf(3, 4, 5), specs.keys)
        assertTrue(specs.values.all { it.movable })
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `slot(...)` 无 `movable`/`placeable` 具名参数。

- [ ] **Step 3: 实现**

修改 `ChestMenuDsl.kt` 中 `ChestMenuScope` 的两个方法与两个扩展函数（`PageableChestMenuScope`、`closeButton` 不动）：

```kotlin
@ChestMenuDsl
interface ChestMenuScope {
    var title: Component
    val type: ChestMenuType

    /**
     * 声明一个槽位。
     * @param movable 槽中物品可被玩家拿起（真实给予经 `onTake` 回调，见 [SlotTakeEvent]）
     * @param placeable 玩家可把自己背包的物品放入本槽（真实扣除经 `onPlace` 回调，见 [SlotPlaceEvent]；
     *   要求菜单以 `hidePlayerInventory = false` 创建）
     */
    fun slot(
        index: Int,
        item: ItemStack,
        movable: Boolean = false,
        placeable: Boolean = false,
        metadata: ItemMeta.() -> Unit = {},
        block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
    )
    fun slot(
        range: IntRange,
        item: ItemStack,
        movable: Boolean = false,
        placeable: Boolean = false,
        metadata: ItemMeta.() -> Unit = {},
        block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
    )
}

fun ChestMenuScope.slot(
    index: Int,
    type: Material,
    amount: Int = 1,
    movable: Boolean = false,
    placeable: Boolean = false,
    metadata: ItemMeta.() -> Unit = {},
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(index, ItemStack(type, amount), movable, placeable, metadata, block)
}
fun ChestMenuScope.slot(
    range: IntRange,
    type: Material,
    amount: Int = 1,
    movable: Boolean = false,
    placeable: Boolean = false,
    metadata: ItemMeta.() -> Unit = {},
    block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null
) {
    slot(range, ItemStack(type, amount), movable, placeable, metadata, block)
}
```

同时在文件头部为 KDoc 引用补充 import：`com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent`、`com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent`。

修改 `ChestMenuBuilder.kt` 的三个方法：

```kotlin
    override fun slot(
        index: Int,
        item: ItemStack,
        movable: Boolean,
        placeable: Boolean,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ) {
        require(index in 0 until size) { "slot must be in range [0, $size]" }
        slots[index] = buildSlot(item, movable, placeable, metadata, block)
    }

    override fun slot(
        range: IntRange,
        item: ItemStack,
        movable: Boolean,
        placeable: Boolean,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ) {
        require(range.first >= 0 && range.last < size) { "slot must be in range [0, $size]" }
        val slot = buildSlot(item, movable, placeable, metadata, block)
        range.forEach { slots[it] = slot }
    }

    private fun buildSlot(
        item: ItemStack,
        movable: Boolean,
        placeable: Boolean,
        metadata: ItemMeta.() -> Unit,
        block: (SlotScope<InventoryClickEvent>.() -> Unit)?,
    ): SlotSpec {
        return SlotBuilder(InventoryClickEvent::class.java)
            .apply { block?.invoke(this) }
            .build(item.also { item.itemMeta = item.itemMeta?.also(metadata) }, movable, placeable)
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder.ChestMenuBuilderTest"`
Expected: PASS（2 个测试）。再跑 `./gradlew :platform-bukkit-impl:compileKotlin` 确认 `PageableChestMenuBuilder.build()` 内部的 `page.slot(index, item) { ... }` 调用（trailing lambda）仍编译。

- [ ] **Step 5: 提交**

```bash
git add platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/type/chest/dsl/ChestMenuDsl.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/builder/ChestMenuBuilder.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/builder/ChestMenuBuilderTest.kt
git commit -m "feat(menu): add movable/placeable parameters to chest slot DSL

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: hidePlayerInventory 配置贯通 + off-by-one 修复

**Files:**
- Modify: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/MenuFactory.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/VirtualMenuManager.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/VirtualChestMenu.kt`

**Interfaces:**
- Consumes: Task 3 的 `playerInventoryWindowSlots` / `requirePlaceableVisible`
- Produces:
  - `MenuFactory.createChestMenu(type: ChestMenuType, hidePlayerInventory: Boolean = true, builder: PageableChestMenuScope.() -> Unit): ChestMenu`（默认参数在接口上；override 不写默认值）
  - `VirtualChestMenu(taskScheduler, title, type, specs, hidePlayerInventory: Boolean = true)`；open 时按配置隐藏；隐藏范围修复为 `playerInventoryWindowSlots(type.size)`

- [ ] **Step 1: 修改 MenuFactory 接口**

`MenuFactory.kt` 中 `createChestMenu` 改为：

```kotlin
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
```

- [ ] **Step 2: 修改 VirtualChestMenu**

`VirtualChestMenu.kt`：构造器追加参数并在 init 中校验；`open` 条件隐藏；隐藏范围用共享辅助函数（新增 import `com.github.mayblock.easylib.impl.bukkit.menu.type.chest.playerInventoryWindowSlots` 不需要——同包；需要 import `requirePlaceableVisible` 同包亦免）：

```kotlin
internal class VirtualChestMenu(
    taskScheduler: TaskScheduler,
    override val title: Component,
    override val type: ChestMenuType,
    specs: Map<Int, SlotSpec>,
    private val hidePlayerInventory: Boolean = true,
) : AbstractVirtualMenu(taskScheduler, specs), ChestMenu {

    override val windowId = windowIdCounter.getAndIncrement()

    init {
        requirePlaceableVisible(hidePlayerInventory, specs)
        startMenu()
    }

    override fun open(player: Player) {
        check(!isDestroyed) { "this menu is destroyed!" }
        player.sendPackets {
            bundle {
                forPlayer {
                    containerOpen(windowId, ContainerType.getByTypeId(type.ordinal)!!, title)
                    syncMenuItems()
                    if (hidePlayerInventory) hidePlayerInventoryItems()
                }
            }
        }
        addViewer(player)
    }
```

`hidePlayerInventoryItems` 改为（修复 off-by-one：原 `type.size - 1 until type.size + 36` 会把最后一个菜单槽位清成空气）：

```kotlin
    private fun PacketScope.PlayerPacketScope.hidePlayerInventoryItems() {
        for (i in playerInventoryWindowSlots(type.size)) {
            containerSetSlot(windowId, 0, i, ItemStack.EMPTY)
        }
    }
```

- [ ] **Step 3: 修改 VirtualMenuManager**

`createChestMenu` override 改为（override 不允许写默认值，默认值由接口提供）：

```kotlin
    override fun createChestMenu(
        type: ChestMenuType,
        hidePlayerInventory: Boolean,
        builder: PageableChestMenuScope.() -> Unit,
    ): com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu =
        register(
            PageableChestMenuBuilder(type) { title, slots ->
                VirtualChestMenu(taskScheduler, title, type, slots, hidePlayerInventory)
            }.apply(builder).build()
        )
```

- [ ] **Step 4: 编译 + 全量测试**

Run: `./gradlew :platform-bukkit-impl:test`
Expected: PASS（此前所有测试仍绿；`requirePlaceableVisible` 与 `playerInventoryWindowSlots` 的行为已由 Task 3 测试覆盖；`VirtualChestMenu` 的构造需要 PacketEvents 静态 API，不做直接单测——由 Task 3 的纯函数测试 + 手动验证覆盖，spec §7 已注明）

- [ ] **Step 5: 提交**

```bash
git add platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/MenuFactory.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/VirtualMenuManager.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/VirtualChestMenu.kt
git commit -m "feat(menu): make hidePlayerInventory configurable and fix hide range off-by-one

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: ChestClickEngine 副作用壳 + VirtualChestMenu 集成

**Files:**
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickEngine.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/VirtualChestMenu.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickEngineTest.kt`

**Interfaces:**
- Consumes: Task 1-5 全部产物；`SlotGrid`/`LiveSlot`；`WrapperPlayClientClickWindow.WindowClickType`；`ClickType`（Bukkit）；`getBukkitClickType()`
- Produces:
  - `internal interface ChestClickRenderer`（见下），由 `VirtualChestMenu` 实现
  - `internal class ChestClickEngine(menu, grid, menuSize, hidePlayerInventory, hasPlaceableSlot, scheduler, publish, renderer, isViewing)`：`fun submit(player, snapshot: ClickSnapshot)`、`fun onViewerRemoved(player)`、`class ClickSnapshot(windowSlot, button, clickType, involvedSlots, bukkitClickType)`

- [ ] **Step 1: 写失败测试**

创建 `ChestClickEngineTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.SlotGrid
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 立即在当前线程执行 Once 任务的假调度器：让 submit → process 同步化，便于断言。 */
private class InlineScheduler : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int { task.onTick(); return 0 }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

/** 录制型渲染器：记录调用而不发包。 */
private class RecordingRenderer : ChestClickRenderer {
    val repaints = mutableListOf<Int>()
    val cursors = mutableListOf<ItemStack?>()
    val emptiedWindowSlots = mutableListOf<Int>()
    val resyncedSlots = mutableListOf<Collection<Int>>()
    val bottomResyncs = mutableListOf<Int>()
    var inventoryUpdates = 0
    override fun repaintSlot(index: Int) { repaints += index }
    override fun sendCursor(player: Player, item: ItemStack?) { cursors += item }
    override fun sendWindowSlotEmpty(player: Player, windowSlot: Int) { emptiedWindowSlots += windowSlot }
    override fun resyncSlots(player: Player, slots: Collection<Int>) { resyncedSlots += slots }
    override fun resyncBottomAfterTransfer(player: Player, clickedWindowSlot: Int) { bottomResyncs += clickedWindowSlot }
    override fun updatePlayerInventory(player: Player) { inventoryUpdates++ }
}

class ChestClickEngineTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private val menuSize = 27
    private lateinit var renderer: RecordingRenderer
    private lateinit var events: MutableList<MenuEvent>
    private var cancelPlace = false
    private var cancelTake = false

    private fun spec(item: ItemStack, movable: Boolean = false, placeable: Boolean = false): SlotSpec =
        SlotBuilder(InventoryClickEvent::class.java).build(item, movable, placeable)

    private fun engine(
        specs: Map<Int, SlotSpec>,
        hide: Boolean = false,
    ): Pair<ChestClickEngine, SlotGrid> {
        renderer = RecordingRenderer()
        events = mutableListOf()
        val grid = SlotGrid(specs)
        val engine = ChestClickEngine(
            menu = mockk<Menu>(relaxed = true),
            grid = grid,
            menuSize = menuSize,
            hidePlayerInventory = hide,
            hasPlaceableSlot = specs.values.any { it.placeable },
            scheduler = InlineScheduler(),
            publish = { e ->
                events += e
                if (e is SlotPlaceEvent && cancelPlace) e.isCancelled = true
                if (e is SlotTakeEvent && cancelTake) e.isCancelled = true
            },
            renderer = renderer,
            isViewing = { true },
        )
        return engine to grid
    }

    private fun player(vararg bukkitSlotItems: Pair<Int, ItemStack>): Player {
        val inv = mockk<PlayerInventory>(relaxed = true)
        every { inv.getItem(any()) } returns null
        bukkitSlotItems.forEach { (slot, item) -> every { inv.getItem(slot) } returns item }
        return mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { isOnline } returns true
            every { inventory } returns inv
        }
    }

    private fun click(engine: ChestClickEngine, player: Player, windowSlot: Int, right: Boolean = false) {
        engine.submit(
            player,
            ChestClickEngine.ClickSnapshot(
                windowSlot = windowSlot,
                button = if (right) 1 else 0,
                clickType = WindowClickType.PICKUP,
                involvedSlots = listOf(windowSlot),
                bukkitClickType = if (right) ClickType.RIGHT else ClickType.LEFT,
            ),
        )
    }

    @Test
    fun `movable 槽位左键拿起：grid 清空、光标写入、重绘`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.STONE, 5), movable = true)))
        val p = player()
        click(engine, p, 5)
        assertTrue(grid[5]!!.item.type.isAir)
        assertEquals(Material.STONE, engine.cursorOf(p)!!.item.type)
        assertEquals(5, engine.cursorOf(p)!!.item.amount)
        assertEquals(listOf(5), renderer.repaints)
        assertEquals(Material.STONE, renderer.cursors.single()!!.type)
    }

    @Test
    fun `从背包拿起再放入 placeable 槽位：派发 SlotPlaceEvent 并提交`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.AIR), placeable = true)))
        val p = player(9 to ItemStack(Material.EMERALD, 3)) // 窗口槽 27 ↔ bukkit 9
        click(engine, p, 27)                                 // 视觉拿起
        assertIs<CursorOrigin.PlayerInventory>(engine.cursorOf(p)!!.origin)
        assertEquals(listOf(27), renderer.emptiedWindowSlots)
        click(engine, p, 5)                                  // 放入
        val place = events.filterIsInstance<SlotPlaceEvent>().single()
        assertEquals(5, place.index); assertEquals(9, place.sourceSlot); assertEquals(3, place.item.amount)
        assertEquals(Material.EMERALD, grid[5]!!.item.type)
        assertNull(engine.cursorOf(p))
        assertEquals(listOf(27), renderer.bottomResyncs)     // 渲染回调的真实扣除
    }

    @Test
    fun `SlotPlaceEvent 取消：grid 不变、光标保留`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.AIR), placeable = true)))
        val p = player(9 to ItemStack(Material.EMERALD, 3))
        click(engine, p, 27)
        cancelPlace = true
        click(engine, p, 5)
        assertTrue(grid[5]!!.item.type.isAir)
        assertEquals(Material.EMERALD, engine.cursorOf(p)!!.item.type)
    }

    @Test
    fun `菜单源光标落入背包区：派发 SlotTakeEvent（index=来源槽），提交后光标清空`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        val p = player()
        click(engine, p, 5)
        click(engine, p, 30) // 窗口槽 30 ↔ bukkit 12
        val take = events.filterIsInstance<SlotTakeEvent>().single()
        assertEquals(5, take.index); assertEquals(12, take.targetSlot); assertEquals(2, take.item.amount)
        assertNull(engine.cursorOf(p))
        assertEquals(listOf(30), renderer.bottomResyncs)
        assertTrue(grid[5]!!.item.type.isAir) // 拿起时已清空且未归还
    }

    @Test
    fun `SlotTakeEvent 取消：光标维持`() {
        val (engine, _) = engine(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        val p = player()
        click(engine, p, 5)
        cancelTake = true
        click(engine, p, 30)
        assertEquals(Material.DIAMOND, engine.cursorOf(p)!!.item.type)
    }

    @Test
    fun `非 PICKUP 点击一律拒绝并权威重刷`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        val p = player()
        engine.submit(
            p,
            ChestClickEngine.ClickSnapshot(5, 0, WindowClickType.QUICK_MOVE, listOf(5), ClickType.SHIFT_LEFT),
        )
        assertEquals(Material.DIAMOND, grid[5]!!.item.type)
        assertEquals(1, renderer.resyncedSlots.size)
        assertTrue(events.single() is InventoryClickEvent) // 兼容：信息性事件照发
    }

    @Test
    fun `onViewerRemoved 把菜单源光标归还来源槽位`() {
        val (engine, grid) = engine(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        val p = player()
        click(engine, p, 5)
        engine.onViewerRemoved(p)
        assertEquals(Material.DIAMOND, grid[5]!!.item.type)
        assertEquals(2, grid[5]!!.item.amount)
        assertNull(engine.cursorOf(p))
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `ChestClickEngine`/`ChestClickRenderer` 未定义。

- [ ] **Step 3: 实现 ChestClickEngine.kt**

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.SlotGrid
import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 引擎的渲染出口：全部包发送/背包重同步经由本接口，由 [VirtualChestMenu] 实现，测试用录制型替身。
 */
internal interface ChestClickRenderer {
    /** 把某菜单槽位的当前物品重绘给所有观看者。命名避开 AbstractVirtualMenu.repaint（protected），防止覆写可见性冲突。 */
    fun repaintSlot(index: Int)

    /** 重发某玩家的虚拟光标权威状态（null=清空）。 */
    fun sendCursor(player: Player, item: ItemStack?)

    /** 把窗口内某槽位刷为空气（用于「视觉拿起」真实物品后该槽显示为空）。 */
    fun sendWindowSlotEmpty(player: Player, windowSlot: Int)

    /** 拒绝路径的权威重刷：菜单区按 grid、背包区按 hide 配置（空气或 updateInventory）。 */
    fun resyncSlots(player: Player, slots: Collection<Int>)

    /** 边界转移提交/取消后的背包区重同步：hide → 点击槽刷空气维持屏蔽；否则 updateInventory。 */
    fun resyncBottomAfterTransfer(player: Player, clickedWindowSlot: Int)

    /** 重发真实背包（仅在背包可见的路径调用）。 */
    fun updatePlayerInventory(player: Player)
}

/**
 * 点击副作用壳：Netty 线程受理点击快照，经 [TaskScheduler]（Once + 同步）串行落到主线程，
 * 用纯核心 [ChestClickLogic] 决策后执行 grid 变更、事件派发与渲染。
 *
 * 主线程串行执行天然避免共享 grid 的并发复合写（异步更新循环仍可能交错覆写 `LiveSlot.item`，
 * 与既有点击-更新竞态一致：last-write-wins，各效果方法做保守的过期快照重检）。
 */
internal class ChestClickEngine(
    private val menu: Menu,
    private val grid: SlotGrid,
    private val menuSize: Int,
    private val hidePlayerInventory: Boolean,
    hasPlaceableSlot: Boolean,
    private val scheduler: TaskScheduler,
    private val publish: (MenuEvent) -> Unit,
    private val renderer: ChestClickRenderer,
    private val isViewing: (Player) -> Boolean,
) {

    /** CLICK_WINDOW 包在 Netty 线程的不可变快照。 */
    class ClickSnapshot(
        val windowSlot: Int,
        val button: Int,
        val clickType: WindowClickType,
        val involvedSlots: List<Int>,
        val bukkitClickType: ClickType,
    )

    private val logic = ChestClickLogic(menuSize, hidePlayerInventory, hasPlaceableSlot)
    private val cursors = ConcurrentHashMap<UUID, VirtualCursor>()

    fun cursorOf(player: Player): VirtualCursor? = cursors[player.uniqueId]

    /** Netty 线程调用：调度到主线程处理（包本身已在监听器中被取消）。 */
    fun submit(player: Player, snapshot: ClickSnapshot) {
        scheduler.scheduleTask { onTick = { process(player, snapshot) } }
    }

    /** 观察者移除（关窗/断线/销毁/窗口被顶替）时调用（任意线程）：主线程清理光标。 */
    fun onViewerRemoved(player: Player) {
        scheduler.scheduleTask { onTick = { cleanupCursor(player) } }
    }

    private fun cleanupCursor(player: Player) {
        val cursor = cursors.remove(player.uniqueId) ?: return
        val origin = cursor.origin as? CursorOrigin.MenuSlot ?: return // 背包源：真实物品从未离开背包，无需处理
        val slot = grid[origin.index] ?: return
        val current = slot.item
        when {
            current.isEmptyStack() -> {
                slot.item = cursor.item
                renderer.repaintSlot(origin.index)
            }
            current.isSimilar(cursor.item) && current.amount + cursor.item.amount <= current.maxStackSize -> {
                slot.item = current.clone().apply { amount += cursor.item.amount }
                renderer.repaintSlot(origin.index)
            }
            else -> logger.debug(
                "discarding virtual cursor item {} x{} of {}: origin slot {} occupied",
                cursor.item.type, cursor.item.amount, player.name, origin.index,
            )
        }
    }

    private fun process(player: Player, snapshot: ClickSnapshot) {
        if (!player.isOnline || !isViewing(player)) return
        // 兼容：对所有点击照常发布信息性 InventoryClickEvent（先于可取消的边界事件）
        snapshot.involvedSlots.forEach { slot ->
            publish(InventoryClickEvent(menu, player, slot, snapshot.bukkitClickType))
        }
        if (snapshot.clickType != WindowClickType.PICKUP) return deny(player, snapshot)
        if (snapshot.button != 0 && snapshot.button != 1) return deny(player, snapshot)
        // -999（窗口外丢弃）及越界一律安全回退
        if (snapshot.windowSlot < 0 || snapshot.windowSlot >= menuSize + 36) return deny(player, snapshot)

        val windowSlot = snapshot.windowSlot
        val right = snapshot.button == 1
        val cursor = cursors[player.uniqueId]
        val menuView = if (windowSlot < menuSize) {
            grid[windowSlot]?.let { SlotView(it.item, it.movable, it.placeable) }
        } else null
        val bottomItem = if (windowSlot >= menuSize) {
            player.inventory.getItem(chestWindowSlotToBukkit(windowSlot, menuSize))
        } else null

        when (val decision = logic.decide(windowSlot, right, cursor, menuView, bottomItem)) {
            is ClickDecision.Deny -> deny(player, snapshot)
            is ClickDecision.PickupFromMenu -> pickupFromMenu(player, snapshot, decision)
            is ClickDecision.PickupFromInventory -> pickupFromInventory(player, decision, bottomItem!!)
            is ClickDecision.PlaceInMenu -> placeInMenu(player, snapshot, decision, cursor!!)
            is ClickDecision.SwapWithMenu -> swapWithMenu(player, snapshot, decision, cursor!!)
            is ClickDecision.PutBackToInventory -> putBack(player)
            is ClickDecision.DropToInventory -> dropToInventory(player, decision, cursor!!)
        }
    }

    /** 拒绝：重发光标真值 + 涉及槽位的权威状态。 */
    private fun deny(player: Player, snapshot: ClickSnapshot) {
        renderer.sendCursor(player, cursors[player.uniqueId]?.item)
        renderer.resyncSlots(player, (snapshot.involvedSlots + snapshot.windowSlot).filter { it >= 0 })
    }

    private fun pickupFromMenu(player: Player, snapshot: ClickSnapshot, d: ClickDecision.PickupFromMenu) {
        val slot = grid[d.slot] ?: return deny(player, snapshot)
        val current = slot.item
        // 决策与提交间可能被异步更新循环覆写：过期则安全回退
        if (current.isEmptyStack() || d.amount > current.amount) return deny(player, snapshot)
        val taken = current.clone().apply { amount = d.amount }
        val remainderAmount = current.amount - d.amount
        slot.item = if (remainderAmount <= 0) ItemStack(Material.AIR)
        else current.clone().apply { amount = remainderAmount }
        cursors[player.uniqueId] = VirtualCursor(taken, CursorOrigin.MenuSlot(d.slot))
        renderer.sendCursor(player, taken)
        renderer.repaintSlot(d.slot)
    }

    private fun pickupFromInventory(player: Player, d: ClickDecision.PickupFromInventory, real: ItemStack) {
        val snapshot = real.clone()
        cursors[player.uniqueId] = VirtualCursor(snapshot, CursorOrigin.PlayerInventory(d.windowSlot))
        renderer.sendCursor(player, snapshot)
        renderer.sendWindowSlotEmpty(player, d.windowSlot) // 物品「在光标上」，槽位视觉置空（真实背包未动）
    }

    private fun placeInMenu(player: Player, snapshot: ClickSnapshot, d: ClickDecision.PlaceInMenu, cursor: VirtualCursor) {
        val slot = grid[d.slot] ?: return deny(player, snapshot)
        val placed = cursor.item.clone().apply { amount = d.amount }
        if (d.fromInventory) {
            val sourceWindowSlot = (cursor.origin as CursorOrigin.PlayerInventory).windowSlot
            val event = SlotPlaceEvent(
                menu, d.slot, player, placed.clone(),
                sourceSlot = chestWindowSlotToBukkit(sourceWindowSlot, menuSize),
            )
            publish(event)
            if (event.isCancelled) {
                renderer.sendCursor(player, cursor.item)
                renderer.repaintSlot(d.slot)
                return
            }
        }
        val current = slot.item
        slot.item = if (current.isEmptyStack()) placed else current.clone().apply { amount += placed.amount }
        val remaining = cursor.item.amount - placed.amount
        if (remaining <= 0) cursors.remove(player.uniqueId)
        else cursors[player.uniqueId] = VirtualCursor(cursor.item.clone().apply { amount = remaining }, cursor.origin)
        renderer.sendCursor(player, cursors[player.uniqueId]?.item)
        renderer.repaintSlot(d.slot)
        if (d.fromInventory) {
            // 渲染 place 回调对真实背包的扣除（placeable 菜单必然 hide=false）
            renderer.resyncBottomAfterTransfer(player, (cursor.origin as CursorOrigin.PlayerInventory).windowSlot)
        }
    }

    private fun swapWithMenu(player: Player, snapshot: ClickSnapshot, d: ClickDecision.SwapWithMenu, cursor: VirtualCursor) {
        val slot = grid[d.slot] ?: return deny(player, snapshot)
        val slotItem = slot.item
        if (slotItem.isEmptyStack()) return deny(player, snapshot) // 过期快照回退
        slot.item = cursor.item
        cursors[player.uniqueId] = VirtualCursor(slotItem, CursorOrigin.MenuSlot(d.slot))
        renderer.sendCursor(player, slotItem)
        renderer.repaintSlot(d.slot)
    }

    private fun putBack(player: Player) {
        cursors.remove(player.uniqueId)
        renderer.sendCursor(player, null)
        renderer.updatePlayerInventory(player) // 背包源光标仅存在于 hide=false 场景
    }

    private fun dropToInventory(player: Player, d: ClickDecision.DropToInventory, cursor: VirtualCursor) {
        val originIndex = (cursor.origin as CursorOrigin.MenuSlot).index
        val event = SlotTakeEvent(
            menu, originIndex, player, cursor.item.clone(),
            targetSlot = chestWindowSlotToBukkit(d.windowSlot, menuSize),
        )
        publish(event)
        if (event.isCancelled) {
            renderer.sendCursor(player, cursor.item)              // 客户端已预测放下 → 恢复光标
            renderer.resyncBottomAfterTransfer(player, d.windowSlot)
            return
        }
        cursors.remove(player.uniqueId)
        renderer.sendCursor(player, null)
        renderer.resyncBottomAfterTransfer(player, d.windowSlot)  // 渲染 take 回调的真实给予
    }

    companion object {
        private val logger by lazy { LoggerFactory.getLogger(ChestClickEngine::class.java) }
    }
}
```

- [ ] **Step 4: 运行引擎测试确认通过**

Run: `./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.ChestClickEngineTest"`
Expected: PASS（7 个测试）

- [ ] **Step 5: 集成进 VirtualChestMenu**

`VirtualChestMenu.kt` 改为完整如下（类声明实现 `ChestClickRenderer`；`handleClickWindow` 交给引擎；三处观察者移除路径挂接光标清理；deny 重刷的 hide 语义收敛到 `resyncSlots`）：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.menu.*
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.bukkit.packet.extension.getBukkitClickType
import com.github.mayblock.easylib.impl.bukkit.util.fromBukkit
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import com.github.mayblock.easylib.packetevents.packet.ContainerType
import com.github.mayblock.easylib.packetevents.packet.dsl.PacketScope
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicInteger

internal class VirtualChestMenu(
    taskScheduler: TaskScheduler,
    override val title: Component,
    override val type: ChestMenuType,
    specs: Map<Int, SlotSpec>,
    private val hidePlayerInventory: Boolean = true,
) : AbstractVirtualMenu(taskScheduler, specs), ChestMenu, ChestClickRenderer {

    override val windowId = windowIdCounter.getAndIncrement()

    private val clickEngine = ChestClickEngine(
        menu = this,
        grid = grid,
        menuSize = type.size,
        hidePlayerInventory = hidePlayerInventory,
        hasPlaceableSlot = specs.values.any { it.placeable },
        scheduler = taskScheduler,
        publish = ::publish,
        renderer = this,
        isViewing = { it in activeViewers },
    )

    init {
        requirePlaceableVisible(hidePlayerInventory, specs)
        startMenu()
    }

    override fun open(player: Player) {
        check(!isDestroyed) { "this menu is destroyed!" }
        player.sendPackets {
            bundle {
                forPlayer {
                    containerOpen(windowId, ContainerType.getByTypeId(type.ordinal)!!, title)
                    syncMenuItems()
                    if (hidePlayerInventory) hidePlayerInventoryItems()
                }
            }
        }
        addViewer(player)
    }

    override fun repaint(index: Int) {
        activeViewers.toList().forEach { player ->
            if (!player.isOnline) return@forEach
            player.sendPackets { forPlayer { updateItem(windowId, index, grid.packetItem(index)) } }
        }
    }

    override fun onClose(player: Player) {
        clickEngine.onViewerRemoved(player)
        player.updateInventory()
    }

    // ── ChestClickRenderer ───────────────────────────────────────────

    override fun repaintSlot(index: Int) = repaint(index)

    override fun sendCursor(player: Player, item: org.bukkit.inventory.ItemStack?) {
        player.sendPackets {
            forPlayer { updateCursorItem(item?.takeUnless { it.isEmptyStack() }?.fromBukkit()) }
        }
    }

    override fun sendWindowSlotEmpty(player: Player, windowSlot: Int) {
        player.sendPackets { forPlayer { updateItem(windowId, windowSlot, ItemStack.EMPTY) } }
    }

    override fun resyncSlots(player: Player, slots: Collection<Int>) {
        val (menuArea, bottomArea) = slots.distinct().partition { it < type.size }
        player.sendPackets {
            forPlayer {
                menuArea.forEach { updateItem(windowId, it, grid.packetItem(it)) }
                if (hidePlayerInventory) bottomArea.forEach { updateItem(windowId, it, ItemStack.EMPTY) }
            }
        }
        if (!hidePlayerInventory && bottomArea.isNotEmpty()) player.updateInventory()
    }

    override fun resyncBottomAfterTransfer(player: Player, clickedWindowSlot: Int) {
        if (hidePlayerInventory) sendWindowSlotEmpty(player, clickedWindowSlot)
        else player.updateInventory()
    }

    override fun updatePlayerInventory(player: Player) {
        player.updateInventory()
    }

    // ── 包监听 ───────────────────────────────────────────────────────

    override fun registerPacketListener(): Disposable =
        BukkitEasyLib.api.packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                when (e.packetType) {
                    PacketType.Play.Client.CLICK_WINDOW -> {
                        val player = e.getPlayer() as? Player ?: return
                        e.isCancelled = handleClickWindow(player, WrapperPlayClientClickWindow(e))
                    }
                    PacketType.Play.Client.CLOSE_WINDOW -> {
                        val player = e.getPlayer() as? Player ?: return
                        e.isCancelled = handleCloseWindow(player, WrapperPlayClientCloseWindow(e))
                    }
                }
            }

            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (player !in activeViewers) return
                when (e.packetType) {
                    PacketType.Play.Server.OPEN_WINDOW -> {
                        val packet = WrapperPlayServerOpenWindow(e)
                        if (packet.containerId != windowId && removeViewer(player)) {
                            clickEngine.onViewerRemoved(player)
                        }
                    }
                }
            }
        })

    private fun handleCloseWindow(player: Player, packet: WrapperPlayClientCloseWindow): Boolean {
        if (packet.windowId != windowId) return false
        return removeViewer(player).ifTrue {
            clickEngine.onViewerRemoved(player)
            player.updateInventory()
        }
    }

    /** Netty 线程：仅做归属判断与快照调度；决策与副作用在主线程串行执行。 */
    private fun handleClickWindow(player: Player, packet: WrapperPlayClientClickWindow): Boolean {
        if (packet.windowId != windowId) return false
        if (player !in activeViewers) return false
        clickEngine.submit(
            player,
            ChestClickEngine.ClickSnapshot(
                windowSlot = packet.slot,
                button = packet.button,
                clickType = packet.windowClickType,
                involvedSlots = packet.hashedSlots.keys.toList(),
                bukkitClickType = packet.getBukkitClickType(),
            ),
        )
        return true
    }

    private fun PacketScope.PlayerPacketScope.syncMenuItems() {
        containerItems(windowId, 0, grid.packetItems(type.size))
    }

    private fun PacketScope.PlayerPacketScope.hidePlayerInventoryItems() {
        for (i in playerInventoryWindowSlots(type.size)) {
            containerSetSlot(windowId, 0, i, ItemStack.EMPTY)
        }
    }

    companion object {
        private val windowIdCounter = AtomicInteger(114514)
    }
}
```

注意：`onClose` 与 `handleCloseWindow`/`OPEN_WINDOW` 顶替三条路径都调用 `clickEngine.onViewerRemoved`（其内部经调度器落主线程，幂等——cursor map remove 原子）。

- [ ] **Step 6: 全量测试**

Run: `./gradlew :platform-bukkit-impl:test`
Expected: PASS（全部测试）

- [ ] **Step 7: 提交**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickEngine.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/VirtualChestMenu.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickEngineTest.kt
git commit -m "feat(menu): wire interactive click engine into VirtualChestMenu

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: spec 校订 + 全量校验

**Files:**
- Modify: `docs/superpowers/specs/2026-07-02-menu-interactive-slots-design.md`

**Interfaces:** 无代码接口；两处实现期发现的 spec 校订。

- [ ] **Step 1: 校订 spec（两处与实现核实结果对齐）**

其一，§5.3 中：

```
处理期间同一玩家的新点击直接拒绝+重刷（per-player pending 标志，主线程置/清）。
```

替换为：

```
点击处理任务经调度器在主线程串行执行，天然互斥，无需额外 pending 标志；处理时校验 `player.isOnline` 与观看状态，过期点击自然丢弃。
```

其二，§6 中：

```
- 事件回调抛异常：按"已取消"处理（回滚 + 日志），保证虚拟状态不被插件 bug 破坏。
```

替换为：

```
- 事件回调抛异常：`SimpleEventBus` 会捕获监听器异常并记日志（不外传），因此经 `onTake`/`onPlace` DSL 注册的回调由构建器包装——异常时先将事件置为取消再重新抛出（日志仍由总线负责），保证「虚拟层不因半失败的真实操作而提交」；经 `menu.on{}` 直接订阅的监听器不受此包装，其异常安全由订阅方自理。
```

- [ ] **Step 2: 全量检查**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL（所有模块编译 + 全部测试通过）

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-07-02-menu-interactive-slots-design.md
git commit -m "docs: align spec with implementation findings (bus exception semantics, serialization)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 手动验证清单（无法自动化的包级行为，交付后在真实服务器冒烟）

1. 默认菜单（不传 hide）：打开后背包被屏蔽，且**最后一个菜单槽位不再被误清**（off-by-one 修复的可见效果）。
2. `hidePlayerInventory = false`：打开后背包可见；随意点击背包区物品被拒绝且**不会视觉消失**。
3. movable 槽位：左键拿起→放回原槽；拿起→点背包区→回调 `addItem` 后物品真实入包。
4. placeable 槽位：从背包拿起→放入→回调扣除后背包数量正确；取消场景（回调置 `isCancelled=true`）物品完好。
5. shift-click/数字键/双击/拖拽：全部无效且视觉即时恢复。
6. 两名玩家同开一个菜单：A 放入的物品 B 可见。
