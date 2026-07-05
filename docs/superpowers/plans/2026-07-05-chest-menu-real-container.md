# Plan A：ChestMenu → 真实 Bukkit 容器 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 ChestMenu 从数据包虚拟菜单改为真实 Bukkit 容器（`Inventory` + `InventoryHolder` + Bukkit 事件监听），default-deny + per-slot movable/placeable 放行，onTake/onPlace 降为门+观察，onUpdate 改主线程，删除 packet 交互引擎；公开 DSL 源码兼容。

**Architecture:** 纯决策核心 `ChestSlotGate`（`InventoryAction` → 放行/取消，无副作用）+ 纯分发核心 `ShiftIntoMenuPlanner`（shift-入菜单只向 placeable 槽分发）+ 副作用壳 `RealChestMenu`（自身即 `InventoryHolder`，持真实 `Inventory`，主线程处理点击/更新/生命周期）+ 全局单一 `MenuInteractionListener`（按 `inventory.holder` 路由 Bukkit 事件）。

**Tech Stack:** Kotlin（工具链 25）、Spigot-API 26.1.2（compileOnly，`createInventory(holder, size, String)`）、adventure `LegacyComponentSerializer`（标题降级）、JUnit + MockK + MockBukkit v26。

**Spec:** `docs/superpowers/specs/2026-07-05-menu-real-container-and-player-overlay-design.md`（已批准；本计划覆盖 Part A / §4）

## Global Constraints

- 构建命令用 wrapper，且必须带 JDK 25：`JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew ...`（本机默认 java 为 1.8）。
- API/实现分离：`platform-bukkit-api` 不依赖 impl；所有新实现类为 `internal`。
- **每个任务用 TDD，测试代码必须写进 `platform-bukkit-impl` 的 test 模块并通过**（用户硬性要求）。
- 依赖版本统一在 `gradle/libs.versions.toml`；仓库在 `buildsrc.convention.repos`。
- 注释/KDoc 用中文；conventional commits，结尾带 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 每个任务结束 `JAVA_HOME=... ./gradlew :platform-bukkit-impl:test` 必须绿。
- GPG 签名可能需交互解锁：实现子代理 **stage 后不要 commit**，由控制器提交（避免非交互环境卡住）。
- 已核实可复用的既有 API（勿重造）：
  - `SlotSpec(item, clickHandlers, updateRules, movable=false, placeable=false)`；`SlotBuilder`；`ChestMenuBuilder`（工厂 lambda `(title, slots)->ChestMenu`）；`PageableChestMenuBuilder`。
  - `Menu : EventSource<MenuEvent>`，含 `open(player)`、`getItem(index): ItemStack?`、`setItem(index, item: ItemStack?)`。
  - `ChestMenu : Menu { val title: Component; val type: ChestMenuType }`；`ChestMenuType(val size: Int)`。
  - 事件：`MenuOpenEvent(menu, player)`、`MenuCloseEvent(menu, player)`；`SlotClickEvent(menu, index, player)`；`InventoryClickEvent(menu, player, index, val type: ClickType) : SlotClickEvent`；`SlotTakeEvent(menu, index, player, item, targetSlot, isCancelled=false)`；`SlotPlaceEvent(menu, index, player, item, sourceSlot, isCancelled=false)`；`SlotUpdateEvent(menu, index, var item: ItemStack)`。
  - `SimpleEventBus<E>()`：`subscribe(EventListener)`、`emit(event)`、`unsubscribeAll()`；`emit` 按 `listener.type.isInstance(event)` 过滤并吞异常记日志。
  - `EventListener<T>(type: Class<out T>, group: String?, handler: T.()->Unit, priority: Priority)`。
  - `internal fun org.bukkit.inventory.ItemStack?.isEmptyStack(): Boolean`（包 `com.github.mayblock.easylib.impl.bukkit.menu`，`MenuExt.kt`）。
  - `TaskScheduler.scheduleTask { trigger=…; isAsync=…; onTick={…} }`；`cancelTask(id)`；`Trigger.Interval(period)`、`Trigger.Once`。
  - MockBukkit：`MockBukkit.mock()`/`unmock()`；`server.addPlayer()`；`MockBukkit.createMockPlugin()`；`Bukkit.createInventory(holder, size, title)`；`player.openInventory(inv): InventoryView`；直接构造 `InventoryClickEvent(view, SlotType.CONTAINER, rawSlot, ClickType, InventoryAction)` 精确控制动作。

---

### Task A1：ChestSlotGate（纯决策核心）

**Files:**
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestSlotGate.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestSlotGateTest.kt`

**Interfaces:**
- Consumes: `org.bukkit.event.inventory.InventoryAction`
- Produces（Task A4 依赖，签名务必一致）:
  - `internal sealed interface SlotDecision`，变体：`Deny`（object）、`AllowNative`（object）、`ShiftIntoMenu`（object）、`class FireTake(val slot: Int)`、`class FirePlace(val slot: Int)`、`class FireSwap(val slot: Int)`
  - `internal object ChestSlotGate { fun decide(isTop: Boolean, rawSlot: Int, action: InventoryAction, movable: Boolean, placeable: Boolean, hidePlayerInventory: Boolean): SlotDecision }`

- [ ] **Step 1: 写失败测试**

创建 `ChestSlotGateTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryAction.*
import kotlin.test.Test
import kotlin.test.assertIs

class ChestSlotGateTest {

    private fun top(action: InventoryAction, movable: Boolean = false, placeable: Boolean = false) =
        ChestSlotGate.decide(isTop = true, rawSlot = 5, action = action, movable = movable, placeable = placeable, hidePlayerInventory = false)

    private fun bottom(action: InventoryAction, hide: Boolean = false) =
        ChestSlotGate.decide(isTop = false, rawSlot = 40, action = action, movable = false, placeable = false, hidePlayerInventory = hide)

    // 顶部：取出
    @Test fun `顶部 PICKUP movable 放行为 FireTake`() {
        listOf(PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, DROP_ALL_SLOT, DROP_ONE_SLOT, MOVE_TO_OTHER_INVENTORY).forEach {
            assertIs<SlotDecision.FireTake>(top(it, movable = true), "$it")
            assertIs<SlotDecision.Deny>(top(it, movable = false), "$it")
        }
    }

    // 顶部：放入
    @Test fun `顶部 PLACE placeable 放行为 FirePlace`() {
        listOf(PLACE_ALL, PLACE_SOME, PLACE_ONE).forEach {
            assertIs<SlotDecision.FirePlace>(top(it, placeable = true), "$it")
            assertIs<SlotDecision.Deny>(top(it, placeable = false), "$it")
        }
    }

    // 顶部：交换（需 movable && placeable）
    @Test fun `顶部 SWAP 与 HOTBAR 需同时 movable 与 placeable`() {
        listOf(SWAP_WITH_CURSOR, HOTBAR_SWAP, HOTBAR_MOVE_AND_READD).forEach {
            assertIs<SlotDecision.FireSwap>(top(it, movable = true, placeable = true), "$it")
            assertIs<SlotDecision.Deny>(top(it, movable = true, placeable = false), "$it")
            assertIs<SlotDecision.Deny>(top(it, movable = false, placeable = true), "$it")
        }
    }

    // 无条件取消 / 放行
    @Test fun `双击收集与创造复制无条件取消`() {
        assertIs<SlotDecision.Deny>(top(COLLECT_TO_CURSOR, movable = true, placeable = true))
        assertIs<SlotDecision.Deny>(bottom(COLLECT_TO_CURSOR))
        assertIs<SlotDecision.Deny>(top(CLONE_STACK, movable = true, placeable = true))
    }

    @Test fun `丢弃光标物品放行原生`() {
        assertIs<SlotDecision.AllowNative>(top(DROP_ALL_CURSOR))
        assertIs<SlotDecision.AllowNative>(top(DROP_ONE_CURSOR))
    }

    @Test fun `NOTHING 与 UNKNOWN 取消`() {
        assertIs<SlotDecision.Deny>(top(NOTHING))
        assertIs<SlotDecision.Deny>(top(UNKNOWN))
    }

    @Test fun `窗口外点击放行原生`() {
        assertIs<SlotDecision.AllowNative>(
            ChestSlotGate.decide(isTop = true, rawSlot = -999, action = PICKUP_ALL, movable = false, placeable = false, hidePlayerInventory = false)
        )
    }

    // 底部
    @Test fun `底部 hide 时一律取消`() {
        listOf(PICKUP_ALL, PLACE_ALL, SWAP_WITH_CURSOR, MOVE_TO_OTHER_INVENTORY).forEach {
            assertIs<SlotDecision.Deny>(bottom(it, hide = true), "$it")
        }
    }

    @Test fun `底部非 hide shift 入菜单为 ShiftIntoMenu`() {
        assertIs<SlotDecision.ShiftIntoMenu>(bottom(MOVE_TO_OTHER_INVENTORY, hide = false))
    }

    @Test fun `底部非 hide 普通操作放行原生`() {
        listOf(PICKUP_ALL, PLACE_ALL, SWAP_WITH_CURSOR, DROP_ALL_SLOT).forEach {
            assertIs<SlotDecision.AllowNative>(bottom(it, hide = false), "$it")
        }
    }
}
```

- [ ] **Step 2: 运行确认编译失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `ChestSlotGate`/`SlotDecision` 未定义。

- [ ] **Step 3: 实现**

创建 `ChestSlotGate.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import org.bukkit.event.inventory.InventoryAction

/**
 * 一次点击经放行门后的决策；副作用由 [RealChestMenu] 执行。纯数据，无副作用。
 */
internal sealed interface SlotDecision {
    /** 取消该 Bukkit 事件（不可变默认）。 */
    data object Deny : SlotDecision

    /** 放行 Bukkit 原生处理，不触发任何菜单事件（如玩家整理自己背包）。 */
    data object AllowNative : SlotDecision

    /** shift-入菜单：取消原生，由引擎手动向 placeable 槽分发。 */
    data object ShiftIntoMenu : SlotDecision

    /** movable 槽被取出：触发 SlotTakeEvent；取消则阻止。 */
    data class FireTake(val slot: Int) : SlotDecision

    /** placeable 槽被放入（光标）：触发 SlotPlaceEvent；取消则阻止。 */
    data class FirePlace(val slot: Int) : SlotDecision

    /** 交换/数字键（需 movable&&placeable）：触发 take+place；任一取消则阻止。 */
    data class FireSwap(val slot: Int) : SlotDecision
}

/**
 * per-slot 放行门（纯逻辑）：默认取消，按 [InventoryAction] 与被作用 slot 的 movable/placeable 放行。
 */
internal object ChestSlotGate {

    fun decide(
        isTop: Boolean,
        rawSlot: Int,
        action: InventoryAction,
        movable: Boolean,
        placeable: Boolean,
        hidePlayerInventory: Boolean,
    ): SlotDecision {
        // 跨全库聚合 / 创造复制：无条件取消
        when (action) {
            InventoryAction.COLLECT_TO_CURSOR, InventoryAction.CLONE_STACK -> return SlotDecision.Deny
            InventoryAction.DROP_ALL_CURSOR, InventoryAction.DROP_ONE_CURSOR -> return SlotDecision.AllowNative
            InventoryAction.NOTHING, InventoryAction.UNKNOWN -> return SlotDecision.Deny
            else -> {}
        }
        if (rawSlot < 0) return SlotDecision.AllowNative // 窗口外
        return if (isTop) decideTop(rawSlot, action, movable, placeable) else decideBottom(action, hidePlayerInventory)
    }

    private fun decideTop(slot: Int, action: InventoryAction, movable: Boolean, placeable: Boolean): SlotDecision =
        when (action) {
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT,
            InventoryAction.MOVE_TO_OTHER_INVENTORY ->
                if (movable) SlotDecision.FireTake(slot) else SlotDecision.Deny

            InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE ->
                if (placeable) SlotDecision.FirePlace(slot) else SlotDecision.Deny

            InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP, InventoryAction.HOTBAR_MOVE_AND_READD ->
                if (movable && placeable) SlotDecision.FireSwap(slot) else SlotDecision.Deny

            else -> SlotDecision.Deny
        }

    private fun decideBottom(action: InventoryAction, hide: Boolean): SlotDecision {
        if (hide) return SlotDecision.Deny
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) return SlotDecision.ShiftIntoMenu
        return SlotDecision.AllowNative
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.ChestSlotGateTest"`
Expected: PASS

- [ ] **Step 5: 暂存（不提交，控制器提交）**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestSlotGate.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestSlotGateTest.kt
```
控制器提交信息：`feat(menu): add ChestSlotGate real-container click decision core`

---

### Task A2：ShiftIntoMenuPlanner（纯分发核心）

**Files:**
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ShiftIntoMenuPlanner.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ShiftIntoMenuPlannerTest.kt`

**Interfaces:**
- Consumes: `org.bukkit.inventory.ItemStack`（`isSimilar`/`maxStackSize`/`amount`）
- Produces（Task A4 依赖）:
  - `internal data class Placement(val slot: Int, val amount: Int)`
  - `internal object ShiftIntoMenuPlanner { fun plan(source: ItemStack, placeableSlots: List<Pair<Int, ItemStack?>>): List<Placement> }`
    - `placeableSlots`：按 slot 索引有序的 `(index, 当前物品或 null)`；只含 placeable 槽。返回把 `source` 分发进去的计划（先填同类未满堆叠、再填空槽，遵守 `maxStackSize`），总量不超过 `source.amount`。

- [ ] **Step 1: 写失败测试**（涉及 `isSimilar`/`maxStackSize`，需 MockBukkit）

创建 `ShiftIntoMenuPlannerTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShiftIntoMenuPlannerTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun stack(m: Material, n: Int) = ItemStack(m, n)

    @Test fun `优先填同类未满堆叠，再填空槽`() {
        val source = stack(Material.STONE, 40)
        val slots = listOf(
            1 to stack(Material.STONE, 60), // 同类，剩 4
            3 to null,                       // 空
            5 to stack(Material.DIRT, 1),    // 异类，跳过
        )
        val plan = ShiftIntoMenuPlanner.plan(source, slots)
        // 先向 slot1 放 4（填满 64），余 36 放入空 slot3
        assertEquals(listOf(Placement(1, 4), Placement(3, 36)), plan)
    }

    @Test fun `空间不足时只分发能放下的量`() {
        val source = stack(Material.STONE, 100)
        val slots = listOf(1 to null) // 一个空槽最多 64
        assertEquals(listOf(Placement(1, 64)), ShiftIntoMenuPlanner.plan(source, slots))
    }

    @Test fun `无可放置空间返回空计划`() {
        val source = stack(Material.STONE, 10)
        val slots = listOf(
            1 to stack(Material.DIRT, 1),     // 异类
            3 to stack(Material.STONE, 64),   // 同类但已满
        )
        assertEquals(emptyList(), ShiftIntoMenuPlanner.plan(source, slots))
    }

    @Test fun `按 slot 顺序分发`() {
        val source = stack(Material.STONE, 5)
        val slots = listOf(7 to null, 2 to null) // 传入顺序即分发顺序
        assertEquals(listOf(Placement(7, 5)), ShiftIntoMenuPlanner.plan(source, slots))
    }
}
```

- [ ] **Step 2: 运行确认编译失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `ShiftIntoMenuPlanner`/`Placement` 未定义。

- [ ] **Step 3: 实现**

创建 `ShiftIntoMenuPlanner.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import org.bukkit.inventory.ItemStack

/** 一次 shift-入菜单向某 placeable 槽放入的量。 */
internal data class Placement(val slot: Int, val amount: Int)

/**
 * shift-入菜单的分发计划（纯逻辑）：把 [source] 按 slot 顺序**只向 placeable 槽**分发，
 * 先填同类未满堆叠、再填空槽，遵守 `maxStackSize`，总量不超过 `source.amount`。
 */
internal object ShiftIntoMenuPlanner {

    fun plan(source: ItemStack, placeableSlots: List<Pair<Int, ItemStack?>>): List<Placement> {
        var remaining = source.amount
        if (remaining <= 0) return emptyList()
        val max = source.maxStackSize
        val result = mutableListOf<Placement>()

        // 第一轮：填同类未满堆叠
        for ((index, current) in placeableSlots) {
            if (remaining <= 0) break
            if (current == null || current.isEmptyStack()) continue
            if (!current.isSimilar(source)) continue
            val space = max - current.amount
            if (space <= 0) continue
            val put = minOf(space, remaining)
            result += Placement(index, put)
            remaining -= put
        }
        // 第二轮：填空槽
        for ((index, current) in placeableSlots) {
            if (remaining <= 0) break
            if (!(current == null || current.isEmptyStack())) continue
            val put = minOf(max, remaining)
            result += Placement(index, put)
            remaining -= put
        }
        return result
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.ShiftIntoMenuPlannerTest"`
Expected: PASS（4 个测试）

- [ ] **Step 5: 暂存**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ShiftIntoMenuPlanner.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ShiftIntoMenuPlannerTest.kt
```
控制器提交信息：`feat(menu): add ShiftIntoMenuPlanner for placeable-only shift distribution`

---

### Task A3：RealChestMenu 核心（真实容器 + 状态 + 生命周期）

**Files:**
- Modify: `gradle/libs.versions.toml`（加 `adventure-text-serializer-legacy`）
- Modify: `platform-bukkit-impl/build.gradle.kts`（引入该依赖）
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuTest.kt`

**Interfaces:**
- Consumes: `SlotSpec`、`ChestMenu`/`ChestMenuType`、`MenuOpenEvent`/`MenuCloseEvent`、`SimpleEventBus`、`EventListener`、`SlotClickEvent`、`Priority`、`isEmptyStack()`、`LegacyComponentSerializer`、Bukkit `Inventory`/`InventoryHolder`
- Produces（Task A4/A6 依赖）:
  - `internal class RealChestMenu(taskScheduler: TaskScheduler, override val title: Component, override val type: ChestMenuType, private val specs: Map<Int, SlotSpec>, private val hidePlayerInventory: Boolean = true) : ChestMenu, InventoryHolder, EventSource<MenuEvent>`
  - `val bukkitInventory: Inventory`（真实容器）；`override fun getInventory(): Inventory`
  - `fun specOf(slot: Int): SlotSpec?`（供门查 movable/placeable）
  - `fun publishOpen(player: Player)` / `fun publishClose(player: Player)`（供监听器调用）
  - `val hideInventory: Boolean get() = hidePlayerInventory`
  - `getItem`/`setItem`/`open`/`destroy`（实现 Menu）

- [ ] **Step 1: 加依赖 + 写失败测试**

在 `gradle/libs.versions.toml` 的 `[libraries]` 加（`adventureApi` 版本已存在）：
```toml
adventure-serializer-legacy = { module = "net.kyori:adventure-text-serializer-legacy", version.ref = "adventureApi" }
```
在 `platform-bukkit-impl/build.gradle.kts` 的 `dependencies {}` 加：
```kotlin
    implementation(libs.adventure.serializer.legacy)
```

创建 `RealChestMenuTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuOpenEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RealChestMenuTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun spec(item: ItemStack) = SlotBuilder(InventoryClickEvent::class.java).build(item)

    private fun menu(specs: Map<Int, SlotSpec>) =
        RealChestMenu(mockk<TaskScheduler>(relaxed = true), Component.text("交易"), ChestMenuType.GENERIC_9X3, specs, hidePlayerInventory = false)

    @Test fun `真实容器尺寸与初始物品`() {
        val m = menu(mapOf(11 to spec(ItemStack(Material.DIAMOND, 3))))
        assertEquals(27, m.bukkitInventory.size)
        assertEquals(Material.DIAMOND, m.bukkitInventory.getItem(11)!!.type)
        assertEquals(3, m.bukkitInventory.getItem(11)!!.amount)
        assertNull(m.bukkitInventory.getItem(0))
    }

    @Test fun `getInventory 返回同一真实容器（holder 即菜单）`() {
        val m = menu(mapOf(0 to spec(ItemStack(Material.STONE))))
        assertEquals(m.bukkitInventory, m.inventory)
        assertEquals(m, m.bukkitInventory.holder)
    }

    @Test fun `getItem setItem 读写真实容器，AIR 视为空`() {
        val m = menu(mapOf(4 to spec(ItemStack(Material.AIR))))
        assertNull(m.getItem(4))
        m.setItem(4, ItemStack(Material.EMERALD, 2))
        assertEquals(Material.EMERALD, m.getItem(4)!!.type)
        m.setItem(4, null)
        assertNull(m.getItem(4))
    }

    @Test fun `setItem 越界抛异常`() {
        val m = menu(mapOf(0 to spec(ItemStack(Material.STONE))))
        assertFailsWith<IllegalArgumentException> { m.setItem(27, ItemStack(Material.STONE)) }
    }

    @Test fun `specOf 暴露 slot 声明`() {
        val s = SlotBuilder(InventoryClickEvent::class.java).build(ItemStack(Material.STONE), movable = true, placeable = true)
        val m = menu(mapOf(6 to s))
        assertEquals(true, m.specOf(6)!!.movable)
        assertNull(m.specOf(7))
    }

    @Test fun `publishOpen publishClose 走事件总线`() {
        val m = menu(mapOf(0 to spec(ItemStack(Material.STONE))))
        val events = mutableListOf<MenuEvent>()
        m.on { on<MenuOpenEvent> { events += this }; on<MenuCloseEvent> { events += this } }
        val p = server.addPlayer()
        m.publishOpen(p); m.publishClose(p)
        assertEquals(2, events.size)
    }

    @Test fun `声明的 onClick 经总线按 index 过滤`() {
        var clicks = 0
        val built = SlotBuilder(InventoryClickEvent::class.java).apply { onClick { clicks++ } }.build(ItemStack(Material.STONE))
        val m = menu(mapOf(2 to built))
        val p = server.addPlayer()
        m.fireClickForTest(p, 2) // 见实现：仅用于测试的 publish 包装
        m.fireClickForTest(p, 3)
        assertEquals(1, clicks)
    }
}
```

- [ ] **Step 2: 运行确认编译失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `RealChestMenu` 未定义。

- [ ] **Step 3: 实现**

创建 `RealChestMenu.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.MenuCloseEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.MenuOpenEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.event.EventListener
import com.github.mayblock.easylib.api.event.EventSource
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.isEmptyStack
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import com.github.mayblock.easylib.impl.event.SimpleEventBus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * 真实容器版箱子菜单：自身即 [InventoryHolder]，持一个真实 [Inventory]（多观看者共享）。
 * 点击/拖拽/开关由 [MenuInteractionListener] 按 holder 路由回本菜单处理（见 Task A4）。
 * 菜单级事件总线仅暴露订阅侧（[EventSource]），`emit` 内部持有。
 */
internal class RealChestMenu(
    private val taskScheduler: TaskScheduler,
    override val title: Component,
    override val type: ChestMenuType,
    private val specs: Map<Int, SlotSpec>,
    private val hidePlayerInventory: Boolean = true,
    private val bus: SimpleEventBus<MenuEvent> = SimpleEventBus(),
) : ChestMenu, InventoryHolder, EventSource<MenuEvent> by bus {

    val bukkitInventory: Inventory =
        Bukkit.createInventory(this, type.size, LegacyComponentSerializer.legacySection().serialize(title))

    val hideInventory: Boolean get() = hidePlayerInventory

    private var destroyed = false
    override val isDestroyed: Boolean get() = destroyed

    init {
        // 初始物品写入真实容器
        specs.forEach { (index, spec) -> if (!spec.item.isEmptyStack()) bukkitInventory.setItem(index, spec.item) }
        // slot 声明的点击处理器挂到菜单总线（按 index 过滤）
        specs.forEach { (index, spec) ->
            spec.clickHandlers.forEach { handler ->
                bus.subscribe(EventListener<SlotClickEvent>(handler.type, null, { if (index == this.index) handler.block(this) }, handler.priority))
            }
        }
    }

    override fun getInventory(): Inventory = bukkitInventory

    fun specOf(slot: Int): SlotSpec? = specs[slot]

    override fun open(player: Player) {
        check(!destroyed) { "this menu is destroyed!" }
        player.openInventory(bukkitInventory) // 触发 InventoryOpenEvent → 监听器 publishOpen
    }

    override fun getItem(index: Int): ItemStack? = bukkitInventory.getItem(index)?.takeUnless { it.isEmptyStack() }

    override fun setItem(index: Int, item: ItemStack?) {
        require(index in 0 until type.size) { "slot $index out of range [0, ${type.size})" }
        bukkitInventory.setItem(index, item ?: ItemStack(Material.AIR))
    }

    fun publish(event: MenuEvent) = bus.emit(event)
    fun publishOpen(player: Player) = bus.emit(MenuOpenEvent(this, player))
    fun publishClose(player: Player) = bus.emit(MenuCloseEvent(this, player))

    /** 仅测试用：直接派发一次 slot 点击事件，验证 index 过滤。 */
    fun fireClickForTest(player: Player, index: Int) =
        bus.emit(InventoryClickEvent(this, player, index, ClickType.LEFT))

    override fun destroy() {
        if (destroyed) return
        bukkitInventory.viewers.toList().forEach { it.closeInventory() }
        bus.unsubscribeAll()
        destroyed = true
    }

    val scheduler: TaskScheduler get() = taskScheduler
}
```

（注：`ChestMenu`/`Menu` 需要的 `isDestroyed`/`destroy` 来自 `Destroyable`；`open`/`getItem`/`setItem` 来自 `Menu`。`taskScheduler` 暴露给 Task A5 的更新循环。）

- [ ] **Step 4: 运行确认通过 + 全模块编译**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenuTest"`
Expected: PASS（7 个测试）。此时 `VirtualChestMenu` 仍在（未删），全模块仍编译。

- [ ] **Step 5: 暂存**

```bash
git add gradle/libs.versions.toml platform-bukkit-impl/build.gradle.kts platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuTest.kt
```
控制器提交信息：`feat(menu): add RealChestMenu core backed by a real Bukkit inventory`

---

### Task A4：点击处理 + MenuInteractionListener（放行门 + 事件 + shift 分发）

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt`（加 `handleClick`/`handleShiftIntoMenu`/`handleClose`）
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuInteractionListener.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuClickTest.kt`

**Interfaces:**
- Consumes: Task A1 `ChestSlotGate`/`SlotDecision`；Task A2 `ShiftIntoMenuPlanner`/`Placement`；`SlotTakeEvent`/`SlotPlaceEvent`/`InventoryClickEvent`；Bukkit `InventoryClickEvent`(事件)/`InventoryCloseEvent`/`InventoryOpenEvent`
- Produces（Task A6 依赖）:
  - `RealChestMenu.handleClick(e: org.bukkit.event.inventory.InventoryClickEvent)`
  - `RealChestMenu.handleClose(player: Player)`（publishClose + hide 恢复）
  - `class MenuInteractionListener : org.bukkit.event.Listener`

- [ ] **Step 1: 写失败测试**

创建 `RealChestMenuClickTest.kt`（直接构造 Bukkit `InventoryClickEvent` 精确控制 `InventoryAction`）：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.MenuEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotPlaceEvent
import com.github.mayblock.easylib.api.bukkit.menu.slot.SlotTakeEvent
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotSpec
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.InventoryView
import org.mockbukkit.mockbukkit.MockBukkit
import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent as ApiInventoryClickEvent
import org.bukkit.event.inventory.InventoryClickEvent as BukkitInventoryClickEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealChestMenuClickTest {

    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    @BeforeTest fun setUp() { server = MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun spec(item: ItemStack, movable: Boolean = false, placeable: Boolean = false) =
        SlotBuilder(ApiInventoryClickEvent::class.java).build(item, movable, placeable)

    private fun menu(specs: Map<Int, SlotSpec>): RealChestMenu =
        RealChestMenu(mockk<TaskScheduler>(relaxed = true), Component.text("t"), ChestMenuType.GENERIC_9X3, specs, hidePlayerInventory = false)

    private fun open(m: RealChestMenu): Pair<Player, InventoryView> {
        val p = server.addPlayer()
        return p to p.openInventory(m.bukkitInventory)
    }

    private fun click(view: InventoryView, rawSlot: Int, action: InventoryAction, click: ClickType = ClickType.LEFT): BukkitInventoryClickEvent =
        BukkitInventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, click, action)

    @Test fun `不可变槽点击被取消`() {
        val m = menu(mapOf(5 to spec(ItemStack(Material.DIAMOND))))
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `movable 槽取出触发 SlotTakeEvent 且不取消`() {
        var take = 0
        val m = menu(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        m.on { on<SlotTakeEvent> { take++ } }
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertEquals(1, take)
        assertFalse(e.isCancelled)
    }

    @Test fun `onTake 取消则阻止取出`() {
        val m = menu(mapOf(5 to spec(ItemStack(Material.DIAMOND, 2), movable = true)))
        m.on { on<SlotTakeEvent> { isCancelled = true } }
        val (_, view) = open(m)
        val e = click(view, 5, InventoryAction.PICKUP_ALL)
        m.handleClick(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `placeable 槽放入触发 SlotPlaceEvent`() {
        var place = 0
        val m = menu(mapOf(5 to spec(ItemStack(Material.AIR), placeable = true)))
        m.on { on<SlotPlaceEvent> { place++ } }
        val (p, view) = open(m)
        view.cursor = ItemStack(Material.EMERALD, 1)
        val e = click(view, 5, InventoryAction.PLACE_ALL)
        m.handleClick(e)
        assertEquals(1, place)
        assertFalse(e.isCancelled)
    }

    @Test fun `信息性 onClick 对已声明槽触发`() {
        var clicks = 0
        val built = SlotBuilder(ApiInventoryClickEvent::class.java).apply { onClick { clicks++ } }.build(ItemStack(Material.BARRIER))
        val m = menu(mapOf(8 to built))
        val (_, view) = open(m)
        m.handleClick(click(view, 8, InventoryAction.PICKUP_ALL))
        assertEquals(1, clicks)
    }

    @Test fun `shift 入菜单只向 placeable 槽分发`() {
        val places = mutableListOf<Int>()
        val m = menu(mapOf(
            0 to spec(ItemStack(Material.STONE, 60), placeable = true), // 同类剩 4
            1 to spec(ItemStack(Material.DIAMOND)),                     // 不可放置
            2 to spec(ItemStack(Material.AIR), placeable = true),       // 空
        ))
        m.on { on<SlotPlaceEvent> { places += index } }
        val (p, view) = open(m)
        p.inventory.setItem(0, ItemStack(Material.STONE, 40)) // 底部第一格
        val rawBottom = m.bukkitInventory.size + 0 // 底部第一格的 rawSlot
        val e = click(view, rawBottom, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        m.handleClick(e)
        assertTrue(e.isCancelled) // 原生被取消，改手动分发
        assertEquals(listOf(0, 2), places) // 先填同类槽0（+4），再填空槽2（+36）
        assertEquals(64, m.bukkitInventory.getItem(0)!!.amount)
        assertEquals(36, m.bukkitInventory.getItem(2)!!.amount)
        assertEquals(Material.DIAMOND, m.bukkitInventory.getItem(1)!!.type) // 槽1（不可放置）未被污染
    }

    @Test fun `拖拽触及不可放置顶部槽则整体取消`() {
        val m = menu(mapOf(0 to spec(ItemStack(Material.AIR), placeable = true), 1 to spec(ItemStack(Material.DIAMOND))))
        val (_, view) = open(m)
        view.cursor = ItemStack(Material.EMERALD, 2)
        // 拖到 placeable 槽0 与不可放置槽1
        val newItems = mapOf(0 to ItemStack(Material.EMERALD, 1), 1 to ItemStack(Material.EMERALD, 1))
        val e = org.bukkit.event.inventory.InventoryDragEvent(view, ItemStack(Material.AIR), ItemStack(Material.EMERALD, 2), false, newItems)
        m.handleDrag(e)
        assertTrue(e.isCancelled)
    }

    @Test fun `拖拽仅触及 placeable 顶部槽则放行并逐槽 onPlace`() {
        var place = 0
        val m = menu(mapOf(0 to spec(ItemStack(Material.AIR), placeable = true), 1 to spec(ItemStack(Material.AIR), placeable = true)))
        m.on { on<SlotPlaceEvent> { place++ } }
        val (_, view) = open(m)
        view.cursor = ItemStack(Material.EMERALD, 2)
        val newItems = mapOf(0 to ItemStack(Material.EMERALD, 1), 1 to ItemStack(Material.EMERALD, 1))
        val e = org.bukkit.event.inventory.InventoryDragEvent(view, ItemStack(Material.AIR), ItemStack(Material.EMERALD, 2), false, newItems)
        m.handleDrag(e)
        assertFalse(e.isCancelled)
        assertEquals(2, place)
    }
}
```

- [ ] **Step 2: 运行确认编译失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `handleClick` 未定义。

- [ ] **Step 3: 实现**

在 `RealChestMenu.kt` 追加以下方法（并 import 所需类型；`ApiInventoryClickEvent` 即已 import 的 `InventoryClickEvent`）：

```kotlin
    /** 由 [MenuInteractionListener] 在主线程调用：按放行门决策处理一次点击。 */
    fun handleClick(e: org.bukkit.event.inventory.InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val rawSlot = e.rawSlot
        val isTop = rawSlot in 0 until type.size
        // 信息性 onClick（已声明的顶部槽，任意点击，先于门/转移事件）
        if (isTop && specs.containsKey(rawSlot)) {
            bus.emit(InventoryClickEvent(this, player, rawSlot, e.click))
        }
        val spec = if (isTop) specs[rawSlot] else null
        val decision = ChestSlotGate.decide(isTop, rawSlot, e.action, spec?.movable ?: false, spec?.placeable ?: false, hidePlayerInventory)
        when (decision) {
            is SlotDecision.Deny -> e.isCancelled = true
            is SlotDecision.AllowNative -> {}
            is SlotDecision.FireTake -> {
                val ev = SlotTakeEvent(this, decision.slot, player, (e.currentItem ?: ItemStack(Material.AIR)).clone(), targetSlot = -1)
                bus.emit(ev)
                if (ev.isCancelled) e.isCancelled = true
            }
            is SlotDecision.FirePlace -> {
                val ev = SlotPlaceEvent(this, decision.slot, player, (e.cursor ?: ItemStack(Material.AIR)).clone(), sourceSlot = -1)
                bus.emit(ev)
                if (ev.isCancelled) e.isCancelled = true
            }
            is SlotDecision.FireSwap -> {
                val take = SlotTakeEvent(this, decision.slot, player, (e.currentItem ?: ItemStack(Material.AIR)).clone(), targetSlot = -1)
                val place = SlotPlaceEvent(this, decision.slot, player, (e.cursor ?: ItemStack(Material.AIR)).clone(), sourceSlot = -1)
                bus.emit(take); bus.emit(place)
                if (take.isCancelled || place.isCancelled) e.isCancelled = true
            }
            is SlotDecision.ShiftIntoMenu -> {
                e.isCancelled = true
                handleShiftIntoMenu(player, e)
            }
        }
    }

    private fun handleShiftIntoMenu(player: Player, e: org.bukkit.event.inventory.InventoryClickEvent) {
        val source = e.currentItem?.takeUnless { it.isEmptyStack() } ?: return
        val placeable = specs.filterValues { it.placeable }.keys.sorted().map { it to bukkitInventory.getItem(it) }
        val plan = ShiftIntoMenuPlanner.plan(source, placeable)
        var placedTotal = 0
        for (p in plan) {
            val placing = source.clone().apply { amount = p.amount }
            val ev = SlotPlaceEvent(this, p.slot, player, placing.clone(), sourceSlot = e.slot)
            bus.emit(ev)
            if (ev.isCancelled) continue
            val existing = bukkitInventory.getItem(p.slot)
            if (existing == null || existing.isEmptyStack()) bukkitInventory.setItem(p.slot, placing)
            else existing.amount += p.amount
            placedTotal += p.amount
        }
        if (placedTotal > 0) {
            val remaining = source.amount - placedTotal
            e.clickedInventory?.setItem(e.slot, if (remaining <= 0) null else source.clone().apply { amount = remaining })
            player.updateInventory()
        }
    }

    /** 由监听器在主线程调用：按 spec §4.3 处理一次拖拽（仅向 placeable 顶部槽放行，否则整体取消）。 */
    fun handleDrag(e: org.bukkit.event.inventory.InventoryDragEvent) {
        val player = e.whoClicked as? Player ?: return
        val topRaw = e.rawSlots.filter { it in 0 until type.size }
        if (topRaw.isEmpty()) { // 仅在底部（玩家背包）内拖拽
            if (hidePlayerInventory) e.isCancelled = true
            return
        }
        if (topRaw.any { specs[it]?.placeable != true }) { e.isCancelled = true; return } // 触及不可放置顶部槽
        for (slot in topRaw) {
            val newItem = e.newItems[slot] ?: continue
            val ev = SlotPlaceEvent(this, slot, player, newItem.clone(), sourceSlot = -1)
            bus.emit(ev)
            if (ev.isCancelled) { e.isCancelled = true; return } // 原生拖拽只能整体取消
        }
    }

    /** 关窗/断线：publishClose + 若隐藏则恢复真实背包显示。 */
    fun handleClose(player: Player) {
        publishClose(player)
        if (hidePlayerInventory) player.updateInventory()
    }
```

创建 `MenuInteractionListener.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent

/**
 * 全局单一监听器：按 `inventory.holder` 把 Bukkit 库存事件路由回对应 [RealChestMenu]。
 * 由 MenuManager 在 plugin 上注册/注销（Task A6）。
 */
internal class MenuInteractionListener : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onClick(e: InventoryClickEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        menu.handleClick(e)
    }

    @EventHandler
    fun onOpen(e: InventoryOpenEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        (e.player as? Player)?.let { menu.publishOpen(it) }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onDrag(e: org.bukkit.event.inventory.InventoryDragEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        menu.handleDrag(e)
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val menu = e.inventory.holder as? RealChestMenu ?: return
        (e.player as? Player)?.let { menu.handleClose(it) }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenuClickTest"`
Expected: PASS（8 个测试：不可变取消、movable 取出、onTake 取消、placeable 放入、信息性 onClick、shift 分发、拖拽触及不可放置取消、拖拽仅 placeable 放行）

- [ ] **Step 5: 暂存**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuInteractionListener.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuClickTest.kt
```
控制器提交信息：`feat(menu): route real-container clicks through slot gate with take/place/shift`

---

### Task A5：onUpdate 主线程更新循环

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt`（启动/停止更新循环）
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuUpdateTest.kt`

**Interfaces:**
- Consumes: `SlotSpec.updateRules`（`UpdateRule(trigger, block: SlotUpdateEvent.()->Unit)`）；`SlotUpdateEvent(menu, index, var item)`；`TaskScheduler`
- Produces: `RealChestMenu.startUpdates()` / `stopUpdates()`（构造末尾 start，`destroy` 时 stop）

- [ ] **Step 1: 写失败测试**（用记录型假 scheduler，立即执行 Once/Interval 的 onTick 一次）

创建 `RealChestMenuUpdateTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** 立即同步执行每个被排的任务一次的假调度器（断言 isAsync=false）。 */
private class InlineScheduler(val asyncFlags: MutableList<Boolean> = mutableListOf()) : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int { asyncFlags += task.isAsync; task.onTick(); return 0 }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

class RealChestMenuUpdateTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test fun `更新规则在主线程 tick 并写入真实容器`() {
        val scheduler = InlineScheduler()
        val spec = SlotBuilder(InventoryClickEvent::class.java).apply {
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                item = ItemStack(Material.CLOCK, 5)
            }
        }.build(ItemStack(Material.AIR))
        val m = RealChestMenu(scheduler, Component.text("t"), ChestMenuType.GENERIC_9X3, mapOf(4 to spec), hidePlayerInventory = false)
        // 构造末尾已 startUpdates → InlineScheduler 立即执行了一次 onTick
        assertEquals(Material.CLOCK, m.bukkitInventory.getItem(4)!!.type)
        assertEquals(5, m.bukkitInventory.getItem(4)!!.amount)
        assertEquals(listOf(false), scheduler.asyncFlags) // 主线程（非异步）
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenuUpdateTest"`
Expected: FAIL —— 未启动更新循环，槽 4 仍为空。

- [ ] **Step 3: 实现**

在 `RealChestMenu.kt` 的 `init {}` 末尾追加 `startUpdates()`；`destroy()` 中 `bus.unsubscribeAll()` 前加 `stopUpdates()`；并加方法（import `SlotUpdateEvent`）：

```kotlin
    private val updateTaskIds = mutableListOf<Int>()

    private fun startUpdates() {
        specs.forEach { (index, spec) ->
            spec.updateRules.forEach { rule ->
                updateTaskIds += taskScheduler.scheduleTask {
                    trigger = rule.trigger
                    isAsync = false // 真实容器 setItem 必须主线程
                    onTick = {
                        val current = bukkitInventory.getItem(index) ?: ItemStack(Material.AIR)
                        val event = SlotUpdateEvent(this@RealChestMenu, index, current.clone()).apply(rule.block)
                        if (event.item != current) bukkitInventory.setItem(index, event.item)
                    }
                }
            }
        }
    }

    private fun stopUpdates() {
        updateTaskIds.forEach(taskScheduler::cancelTask)
        updateTaskIds.clear()
    }
```

需要 import：`com.github.mayblock.easylib.api.bukkit.menu.slot.SlotUpdateEvent`。

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenuUpdateTest"`
Expected: PASS

- [ ] **Step 5: 暂存**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuUpdateTest.kt
```
控制器提交信息：`feat(menu): tick RealChestMenu slot updates on the main thread`

---

### Task A6：接线 MenuManager / BukkitEasyLib + 删除 packet 引擎

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/VirtualMenuManager.kt`（构造真实菜单、加 plugin、注册监听器、hide 打包）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/BukkitEasyLib.kt`（传 plugin）
- Delete: `.../menu/type/chest/VirtualChestMenu.kt`、`.../menu/type/chest/ChestClickLogic.kt`、`.../menu/type/chest/ChestClickEngine.kt`
- Delete: `.../test/.../menu/type/chest/ChestClickLogicTest.kt`、`.../test/.../menu/type/chest/ChestClickEngineTest.kt`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/VirtualMenuManagerChestTest.kt`

**Interfaces:**
- Consumes: `RealChestMenu`、`MenuInteractionListener`、`PageableChestMenuBuilder`、`MenuFactory`/`MenuRegistry`、`Plugin`
- Produces: `VirtualMenuManager(taskScheduler, plugin)`；`createChestMenu` 产 `RealChestMenu`；打开时按 `hideInventory` 发包屏蔽玩家背包

- [ ] **Step 1: 写失败测试**

创建 `VirtualMenuManagerChestTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.slot
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VirtualMenuManagerChestTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    private fun manager() = VirtualMenuManager(mockk<TaskScheduler>(relaxed = true), MockBukkit.createMockPlugin())

    @Test fun `createChestMenu 产出真实容器菜单`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3) {
            page(Component.text("t")) { slot(0, Material.DIAMOND) }
        }
        assertTrue(menu is RealChestMenu)
        assertEquals(Material.DIAMOND, (menu as RealChestMenu).bukkitInventory.getItem(0)!!.type)
    }

    @Test fun `placeable 且 hide=true 构建期报错`() {
        val mgr = manager()
        assertFailsWith<IllegalArgumentException> {
            mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = true) {
                page(Component.text("t")) { slot(0, Material.AIR, placeable = true) }
            }
        }
    }
}
```

（`requirePlaceableVisible` 校验在既有代码里已随 movable/placeable 引入——若当前位于被删的 `VirtualChestMenu`，本任务将其移入 `RealChestMenu` 的 `init`。实现时确认该校验存在于 `RealChestMenu`。）

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `VirtualMenuManager` 构造函数尚无 plugin 参数。

- [ ] **Step 3: 实现**

在 `RealChestMenu.kt` 的 `init {}` 开头加校验（从被删的 VirtualChestMenu 迁移 `requirePlaceableVisible`；该函数在 `ChestClickLogic.kt` 中定义并将随之删除，故把它就地内联到 RealChestMenu）：

```kotlin
        require(!(hidePlayerInventory && specs.values.any { it.placeable })) {
            "placeable slots require hidePlayerInventory = false"
        }
```

改 `VirtualMenuManager.kt`：构造函数加 `plugin`，注册监听器，`createChestMenu` 造 `RealChestMenu`，打开时按 hide 发包。完整替换该文件为：

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
import com.github.mayblock.easylib.api.bukkit.menu.type.player.PlayerInventoryMenu
import com.github.mayblock.easylib.api.bukkit.menu.type.player.dsl.PlayerMenuScope
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu
import com.github.mayblock.easylib.impl.bukkit.menu.type.chest.builder.PageableChestMenuBuilder
import com.github.mayblock.easylib.impl.bukkit.menu.type.player.VirtualPlayerInventoryMenu
import com.github.mayblock.easylib.impl.bukkit.menu.type.player.builder.PlayerMenuBuilder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import java.io.Closeable

class VirtualMenuManager(
    private val taskScheduler: TaskScheduler,
    plugin: Plugin,
) : MenuFactory, MenuRegistry, Closeable {

    private val menus = mutableListOf<Menu>()
    private val activeMenus = mutableMapOf<Player, Menu>()
    private val listener = MenuInteractionListener().also { Bukkit.getPluginManager().registerEvents(it, plugin) }

    override fun getActiveMenu(player: Player): Menu? = activeMenus[player]
    override fun hasActiveMenu(player: Player): Boolean = activeMenus.containsKey(player)
    override fun getViewers(menu: Menu): Set<Player> = activeMenus.filterValues { it === menu }.keys

    override fun createPlayerInventoryMenu(builder: PlayerMenuScope.() -> Unit): PlayerInventoryMenu =
        register(PlayerMenuBuilder { slots -> VirtualPlayerInventoryMenu(taskScheduler, slots) }.apply(builder).build())

    override fun createChestMenu(type: ChestMenuType, hidePlayerInventory: Boolean, builder: PageableChestMenuScope.() -> Unit): ChestMenu =
        register(
            PageableChestMenuBuilder(type) { title, slots ->
                RealChestMenu(taskScheduler, title, type, slots, hidePlayerInventory)
            }.apply(builder).build()
        )

    private fun <M : Menu> register(menu: M): M {
        menus += menu
        menu.on {
            on<MenuOpenEvent> { activeMenus[player] = menu }
            on<MenuCloseEvent> { if (activeMenus[player] === menu) activeMenus.remove(player) }
        }
        return menu
    }

    override fun close() {
        HandlerList.unregisterAll(listener)
        menus.forEach { it.destroy() }
        menus.clear()
        activeMenus.clear()
    }
}
```

（hide 的视觉屏蔽在 Task A7 实现；A6 只让 `hidePlayerInventory` 配置贯通——`ChestSlotGate` 已在 hide=true 时取消底部点击（Task A1 已测），`requirePlaceableVisible` 已在 `RealChestMenu.init` 强制 placeable 与 hide 的矛盾报错。）

改 `BukkitEasyLib.kt`：`override val menuFactory = VirtualMenuManager(taskScheduler)` → `VirtualMenuManager(taskScheduler, plugin)`。`plugin` 已是构造参数。

删除文件：
```bash
git rm platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/VirtualChestMenu.kt \
       platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickEngine.kt \
       platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickLogic.kt \
       platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickLogicTest.kt \
       platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/ChestClickEngineTest.kt
```

- [ ] **Step 4: 全量测试 + check**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿（新 chest 测试 + 保留的既有测试；已删测试随文件移除）。
Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew check`
Expected: BUILD SUCCESSFUL（全模块）。

- [ ] **Step 5: 暂存**

```bash
git add -A platform-bukkit-impl gradle
```
控制器提交信息：`feat(menu): switch ChestMenu to real container and remove packet click engine`

---

### Task A7：hidePlayerInventory 视觉屏蔽（数据包发包拦截）

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt`（hide 时注册发包拦截、维护 hideViewers、destroy 释放）
- Create: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/HideBottom.kt`（纯 slot 范围工具）
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/HideBottomTest.kt`

**Interfaces:**
- Consumes: `BukkitEasyLib.api.packetManager`、PacketEvents `WrapperPlayServerWindowItems`（`.windowId`/`.items`）/`WrapperPlayServerSetSlot`（`.windowId`/`.slot`/`.item`）、`Disposable`
- Produces: `internal fun hiddenBottomIndices(menuSize: Int, windowSize: Int): IntRange`

**背景：** 真实容器窗口的 WINDOW_ITEMS/SET_SLOT 包含玩家背包区（`>= menuSize`）。hide=true 时对这些区域发空气（复用 `VirtualPlayerInventoryMenu` 已验证的发包拦截模式，但 windowId 判定相反：容器窗口 `windowId != 0`）。hide 默认为 true（保持原 VirtualChestMenu 行为）。关闭时 `handleClose` 的 `player.updateInventory()` 恢复。**包层行为按 spec §6 交真机手动验证**；本任务的可单测部分是 `hiddenBottomIndices` 与 hideViewers 的增删。

- [ ] **Step 1: 写失败测试**

创建 `HideBottomTest.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import kotlin.test.Test
import kotlin.test.assertEquals

class HideBottomTest {
    @Test fun `隐藏区为容器尺寸到窗口末尾（36 格玩家背包）`() {
        assertEquals(27 until 63, hiddenBottomIndices(27, 63)) // 9x3：27 容器 + 36 背包
        assertEquals(54 until 90, hiddenBottomIndices(54, 90)) // 9x6
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:compileTestKotlin`
Expected: FAIL —— `hiddenBottomIndices` 未定义。

- [ ] **Step 3: 实现**

创建 `HideBottom.kt`：

```kotlin
package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

/** 容器窗口中需屏蔽的玩家背包区窗口 slot 范围（容器尺寸之后的 36 格）。 */
internal fun hiddenBottomIndices(menuSize: Int, windowSize: Int): IntRange = menuSize until windowSize
```

在 `RealChestMenu.kt` 增加 hide 发包拦截（import：`com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib`、`com.github.mayblock.easylib.api.util.Disposable`、`com.github.retrooper.packetevents.event.*`、`com.github.retrooper.packetevents.protocol.packettype.PacketType`、`com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems`、`...WrapperPlayServerSetSlot`、`java.util.concurrent.ConcurrentHashMap`）：

```kotlin
    private val hideViewers = ConcurrentHashMap.newKeySet<Player>()
    private val hidePacketSub: Disposable? = if (hidePlayerInventory) registerHideListener() else null

    private fun registerHideListener(): Disposable =
        BukkitEasyLib.api.packetManager.registerListener(object : com.github.retrooper.packetevents.event.PacketListener {
            override fun onPacketSend(e: com.github.retrooper.packetevents.event.PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (player !in hideViewers) return
                when (e.packetType) {
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        if (packet.windowId == 0) return
                        val items = packet.items.toMutableList()
                        for (i in hiddenBottomIndices(type.size, items.size)) items[i] = com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                        packet.items = items
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId == 0) return
                        if (packet.slot >= type.size) packet.item = com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                    }
                }
            }
        })
```

修改 `publishOpen` / `handleClose` 维护 hideViewers：

```kotlin
    fun publishOpen(player: Player) {
        if (hidePlayerInventory) hideViewers += player
        bus.emit(MenuOpenEvent(this, player))
    }
```
在 `handleClose` 开头加：`hideViewers -= player`。

在 `destroy()` 中释放：`hidePacketSub?.dispose()`（在 `bus.unsubscribeAll()` 之前）。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew :platform-bukkit-impl:test`
Expected: 全绿。
Run: `JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew check`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 暂存**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/HideBottom.kt platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/HideBottomTest.kt
```
控制器提交信息：`feat(menu): blank player inventory via packets when hidePlayerInventory is on`

---

## 计划末尾：交手动验证 / 后续的项

- **hide 发包拦截的运行时行为**（Task A7）：MockBukkit 无 PacketEvents，包重写作为真机手动验证；已单测 `hiddenBottomIndices` 与 hideViewers 增删。
- **onTake/onPlace 的 targetSlot/sourceSlot 精确值**：原生移动下 best-effort，本计划置 -1（shift 路径 sourceSlot 为真实来源槽），KDoc 注明。
- **`simulateInventoryClick` 的动作推断**：本计划的点击集成测试直接构造 `InventoryClickEvent` 精确控制 `InventoryAction`，不依赖 MockBukkit 的动作推断；真机上各 `InventoryAction` 由客户端产生，纳入手动验证清单。

## 手动验证清单（真机冒烟）

1. 不可变展示菜单：任何点击/ shift/拖拽/数字键/双击都无法取出物品。
2. movable 槽：左键/ shift 取出触发 onTake，取消则挡回。
3. placeable 槽：光标放入、拖拽放入、shift 分发触发 onPlace；无空间时 shift 不移动。
4. hide=true：打开后玩家背包被屏蔽，关闭恢复；hide=false 背包可见且可整理。
5. 多玩家共享同一菜单：A 放入 B 可见。
6. 断线：InventoryCloseEvent 原生触发，MenuCloseEvent 正常、无泄漏。
