# 菜单/覆盖层注册表解耦 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除注册表与被注册对象之间的双向耦合。菜单侧：删除 `BukkitMenu.owner`（改为注册表自查名册）、删除 `RealChestMenu.onDestroyed`（改为派发 `MenuDestroyEvent`）。覆盖层侧：删除 `PlayerOverlayImpl.onDestroyed`（改为派发 `OverlayDestroyEvent`）。

**Architecture:** 病根同一个 —— 注册表的记账通过「往被注册对象身上写东西」实现。分两类修：（1）**路由反转**，`MenuInteractionListener` 不再问菜单「你归谁」，改问 manager「这个是你的吗」，`owner` 随之删除、环断开 —— 这一步同时让 `menus` 名册**首次可观察**（经 `route()`），是后续能被测试的前提；（2）**回调换事件**，销毁记账改为在各自既有的同步事件总线上派发销毁事件，注册方以 `Priority.MONITOR` 垫底订阅，回调槽字段删除。

**Tech Stack:** Kotlin/JVM，Gradle（Kotlin DSL），JUnit Platform + MockK + MockBukkit。

**设计文档:** `docs/superpowers/specs/2026-07-17-menu-registry-decoupling-design.md`

## Global Constraints

- **目标平台仅支持 Minecraft 26.1.2+（协议 775+），不考虑向下兼容。** 不写兼容分支。
- **本项目是库（Lib），为上游调用方设计。** 仓库内零引用 ≠ 死代码。本计划不删除任何 public 符号 —— 所有被删符号均为 `internal` 或 `private`。**若实施中发现需要删除或收缩任何 public 符号的可见性，停下并询问维护者。**
- **JVM 工具链为 Java 25。运行任何 Gradle 命令前必须先 `export JAVA_HOME="D:/Program Files/Zulu/zulu-25"`** —— PATH 上 zulu-8 排在前面，否则报 "Gradle requires JVM 17 or later"。
- **提交模式：implementer 只 `git add` 暂存，绝不 `git commit`。** 提交由 controller 统一完成（GPG 签名）。每个 Task 末尾只暂存并报告。
- **工作区有维护者的 WIP，绝对不要碰**：未跟踪文件 `CLAUDE.md`、`common-impl/src/main/kotlin/com/github/mayblock/easylib/impl/util/map/`、`platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/extension/OfflinePlayerInventory.kt`。**只 `git add` 本任务明确列出的文件路径，禁止 `git add .` / `git add -A`。**
- 依赖版本统一在 `gradle/libs.versions.toml`，不在模块内写死。本计划不新增依赖。
- 依赖方向单向：impl → API、platform → common。禁止反向。
- 事件优先级语义：**数值越大越晚执行**（`SimpleEventBus.kt:28` 升序 `sortBy` + `:41` 顺序 `emit`）。`sortBy` 为稳定排序 → 同优先级按订阅顺序。

## 起点状态（重要）

**工作区当前编译不过。** 维护者已手动开始改动并停在半途：
- `OverlayManager.kt` 的 `trackedCount` 已被删除（Task 0 无需再删）。
- `OverlayManager.kt:38` 现为一个孤立的 `.`（原 `.let(::track)` 被吃掉半行）→ **语法错误**。
- `OverlayManagerTest.kt:54,56` 仍引用已删除的 `trackedCount` → 测试侧编译失败。
- `RealChestMenu.kt:47` 有一处未提交的尾逗号删除（`MenuEventDispatcher(),` → `MenuEventDispatcher()`），无害，随 Task 1 一并提交。

Task 0 的唯一目的就是把树修绿，之后才谈得上跑验证。

## File Structure

| 文件 | 职责 | 本计划中的变更 |
|---|---|---|
| `common-api/.../api/util/Priority.kt` | 事件优先级值类型 | Task 2：新增 `MONITOR` 常量 |
| `platform-bukkit-api/.../menu/MenuEvent.kt` | 菜单事件公开 API | Task 2：新增 `MenuDestroyEvent` |
| `platform-bukkit-api/.../overlay/slot/event/OverlayEvent.kt` | 覆盖层事件公开 API | Task 3：新增 `OverlayDestroyEvent` |
| `platform-bukkit-impl/.../menu/BukkitMenu.kt` | 平台内部菜单契约 | Task 1：删除 `owner` |
| `platform-bukkit-impl/.../menu/MenuManager.kt` | 菜单工厂 + 注册表 | Task 1：名册改身份 set、新增 `route`、`register` 转 internal；Task 2：记账改事件订阅、删 `forget` |
| `platform-bukkit-impl/.../menu/MenuInteractionListener.kt` | Bukkit 事件路由 | Task 1：路由委托给 manager |
| `platform-bukkit-impl/.../menu/type/chest/RealChestMenu.kt` | 箱子菜单实现 | Task 1：删 `override var owner`；Task 2：删 `onDestroyed` 参数、destroy 链派发事件 |
| `platform-bukkit-impl/.../overlay/OverlayManager.kt` | 覆盖层工厂 | Task 0：修复语法错误；Task 3：`track()` 内联并删除、记账改事件订阅 |
| `platform-bukkit-impl/.../overlay/PlayerOverlayImpl.kt` | 覆盖层实现 | Task 3：删 `onDestroyed` 字段、destroy 链派发事件 |

---

### Task 0: 修复工作区 —— 恢复编译 + 移除侵入式测试

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayManager.kt:38`
- Modify: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayManagerTest.kt`

**Interfaces:**
- 无产出。本任务只恢复编译并删除测试。

**背景（勿跳过）：** `trackedCount` 生产侧零调用者，纯为一句断言而存在于 main source；维护者已亲手删除。该测试的 KDoc 自承 `create()` 在单测跑不了（PacketEvents 单例），故它手工构造 `PlayerOverlayImpl` 再直接调 `track()` —— 测的是 `track()` 的接线而非真实路径，覆盖本就是残的。**明确丢掉的覆盖：** `OverlayManager` 中「destroy → 从 overlays 摘除」的接线若被误删，测试全绿；此后仅由 spec §6 的人工验证兜底。**维护者已知悉并批准此取舍，不要试图挽救这个测试或另造 API 来测它。**

- [ ] **Step 1: 确认起点确实是坏的**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:compileKotlin
```
Expected: FAIL，`OverlayManager.kt:38` 报语法错误（`Expecting an element`）

- [ ] **Step 2: 恢复 `OverlayManager.create()` 的尾部调用**

`OverlayManager.kt:30-38` 当前为：
```kotlin
    override fun create(block: PlayerOverlayScope.() -> Unit): PlayerOverlay =
        PlayerOverlayBuilder { slots ->
            val map = SlotMap(slots)
            PlayerOverlayImpl(slots, map, taskScheduler, BukkitAsyncExecutor(plugin), PacketOverlayTransport(map))
        }
            .apply(block)
            .build()
            .let { it as PlayerOverlayImpl }
            .
```
把最后一行的孤立 `.` 改回：
```kotlin
            .let(::track)
```
**本任务不要动 `track()`** —— Task 3 会把它内联掉。这里只求编译通过。

- [ ] **Step 3: 删除侵入式测试用例及其专属夹具**

`OverlayManagerTest.kt` 删除 `:47-57`：
```kotlin
    // 说明：同样为绕开 PacketOverlayTransport/PacketEvents，这里直接用 track() 注入假实现，
    // 单独验证「destroy 触发 onDestroyed -> manager 摘除」这条泄漏链修复逻辑，不经过 create()。
    @Test
    fun `overlay destroy 后 manager 不再持有`() {
        val mgr = OverlayManager(mockk<TaskScheduler>(relaxed = true), MockBukkit.createMockPlugin())
        val overlay = fakeOverlay(mockk<TaskScheduler>(relaxed = true), mockk<TaskExecutor>(relaxed = true))
        mgr.track(overlay)
        assertEquals(1, mgr.trackedCount)
        overlay.destroy()
        assertEquals(0, mgr.trackedCount)
    }
```

删除 `:15-29`（该测试删除后即无引用者）：
```kotlin
/** 不触碰 PacketEvents 的最小 [com.github.mayblock.easylib.impl.bukkit.overlay.transport.OverlayTransport] 假实现，专供 [OverlayManager] 的跟踪/摘除逻辑测试。 */
private class NoopTransport : OverlayTransport {
    override fun paintAll(player: Player) {}
    override fun paint(player: Player, slot: Int) {}
    override fun restore(player: Player) {}
    override fun attach(callbacks: OverlayTransport.Callbacks): Disposable = Disposable {}
}

private fun fakeOverlay(
    scheduler: TaskScheduler,
    executor: TaskExecutor
): PlayerOverlayImpl {
    val map = SlotMap(emptyMap())
    return PlayerOverlayImpl(emptyMap(), map, scheduler, executor, NoopTransport())
}
```

删除随之未使用的 import（`:3,5,7,8,10`）：
```kotlin
import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.overlay.transport.OverlayTransport
import org.bukkit.entity.Player
```

删除后文件应只剩 `OverlayManagerTest` 类与其唯一测试 `注册 quit 清理监听器，close 幂等且注销监听器`，import 保留 `TaskScheduler`、`OverlayQuitListener`、`mockk`、`PlayerQuitEvent`、`MockBukkit`、`kotlin.test.*`。

- [ ] **Step 4: 全量构建，确认树已修绿**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 暂存（不要提交）**

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayManager.kt \
        platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayManagerTest.kt
git status --short
```
报告 `git status --short` 的输出。**不要 commit** —— controller 负责提交。

---

### Task 1: 路由反转 —— 删除 `BukkitMenu.owner`

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/BukkitMenu.kt:16-24`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManager.kt:1-16,24,55-63`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuInteractionListener.kt:14-27`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt:50-51`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuInteractionListenerTest.kt:38-64,66-111`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManagerChestTest.kt`（新增 1 个测试）

**Interfaces:**
- Produces: `internal fun MenuManager.route(holder: InventoryHolder?): BukkitMenu?` —— Task 2 用它做断言。
- Produces: `internal fun <M : Menu> MenuManager.register(menu: M): M`（由 `private` 提升）—— 测试用它构造归属关系。
- Produces: `BukkitMenu` 接口不再有 `owner` 成员。

- [ ] **Step 1: 先跑一遍现有菜单测试，确认起点是绿的**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.*"
```
Expected: PASS（`MenuInteractionListenerTest` 3 个 + `MenuManagerChestTest` 5 个及其他菜单测试）

- [ ] **Step 2: 改写 `MenuInteractionListenerTest`，用 `register()` 取代 `owner =` 赋值**

这一步会编译失败（`register` 还是 private、`owner` 还在），这是预期的 —— 它就是本任务的失败测试。

从 `FakeHopperMenu` 删除这一行（`:42`）：
```kotlin
        override var owner: MenuManager? = null
```

`:68` 与 `:89` 两处相同的：
```kotlin
        val menu = FakeHopperMenu().apply { owner = mgr }
```
均改为：
```kotlin
        val menu = FakeHopperMenu().also { mgr.register(it) }
```

`:101`：
```kotlin
        val menu = FakeHopperMenu().apply { owner = other }
```
改为：
```kotlin
        val menu = FakeHopperMenu().also { other.register(it) }
```

类 KDoc（`:20`）从：
```kotlin
 * 监听器 UI 无关性验收：路由只认 [BukkitMenu] 接口 + owner 归属，对具体 UI 类型零感知。
```
改为：
```kotlin
 * 监听器 UI 无关性验收：路由只认 [BukkitMenu] 接口，归属由 [MenuManager] 查自己的名册裁定，
 * 监听器与菜单对具体 UI 类型均零感知。
```

- [ ] **Step 3: 运行测试，确认因编译失败而红**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.MenuInteractionListenerTest"
```
Expected: 编译失败，`Cannot access 'register': it is private in 'MenuManager'`（`owner` 尚未删除，故 `FakeHopperMenu` 会另报 "object is not abstract and does not implement abstract member owner"）

- [ ] **Step 4: 从 `BukkitMenu` 删除 `owner`**

`BukkitMenu.kt` 删除这段（`:18-24`）：
```kotlin
    /**
     * 登记本菜单的 manager；由 [MenuManager] 的 register() 赋值，[MenuInteractionListener]
     * 据此校验事件归属（多 manager 防重复处理）。直接构造的测试实例可手动赋值。
     * 声明为 `var`（而非简报草图中的 `val`）是刻意取舍：manager 只持有 [BukkitMenu] 引用、
     * 对具体 UI 类型零感知，若声明为 `val` 则 register() 无法经接口类型完成赋值，
     * 只能反过来向下转型到具体实现类——恰是本次解耦要消除的耦合。
     */
    var owner: MenuManager?
```

接口体只保留 6 个方法（`handleOpen` / `handleClick` / `handleDrag` / `handleClose` / `getItem` / `setItem`），顺序不变。若删除后 `MenuManager` 的 import 变为未使用，一并删除。

- [ ] **Step 5: `MenuManager` —— 名册改身份 set、新增 `route`、`register` 提升为 internal**

import 区（`:1-16`）新增：
```kotlin
import org.bukkit.inventory.InventoryHolder
import java.util.Collections
import java.util.IdentityHashMap
```

`:24` 从：
```kotlin
    private val menus = mutableListOf<Menu>()
```
改为：
```kotlin
    // 身份语义（而非 equals）：与 activeMenus 的 `it.value === menu`、getViewers 的 `it === menu` 对齐。
    // 顺带使 route() 的归属判定为 O(1)。
    private val menus: MutableSet<Menu> = Collections.newSetFromMap(IdentityHashMap())
```

`:54-63` 的 `register` 从：
```kotlin
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
```
改为（订阅块内容一字未动，仅删 `owner` 赋值行 + 改可见性；`route` 新增）：
```kotlin
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
```

- [ ] **Step 6: `MenuInteractionListener` 路由委托给 manager**

`:25-27` 从：
```kotlin
    /** holder → 菜单：仅路由实现了 [BukkitMenu] 且归属本 manager 的实例。 */
    fun route(holder: InventoryHolder?): BukkitMenu? =
        (holder as? BukkitMenu)?.takeIf { it.owner === owner }
```
改为：
```kotlin
    /** holder → 菜单：归属裁定交给 [MenuManager] 自查名册（见 [MenuManager.route]）。 */
    fun route(holder: InventoryHolder?): BukkitMenu? = owner.route(holder)
```

类 KDoc（`:19-21`）从：
```kotlin
 * 每个 [MenuManager] 实例各自持有并注册一个本监听器；当同一 server 上存在多个
 * MenuManager 时，各自的监听器都会收到全局的 Bukkit 事件，因此路由前必须校验
 * 目标菜单确实属于本监听器所属的 [owner]，否则同一事件会被多个 manager 重复处理。
```
改为：
```kotlin
 * 每个 [MenuManager] 实例各自持有并注册一个本监听器；当同一 server 上存在多个
 * MenuManager 时，各自的监听器都会收到全局的 Bukkit 事件，因此路由前必须裁定归属，
 * 否则同一事件会被多个 manager 重复处理。归属由 [owner] 查自己的名册回答，
 * 菜单对象自身不携带归属信息。
```

`import org.bukkit.inventory.InventoryHolder` 仍在使用（`route` 的参数类型），保留。

- [ ] **Step 7: `RealChestMenu` 删除 `owner` 覆盖**

`:50-51` 删除：
```kotlin
    /** 登记本菜单的 manager；由 register() 赋值，供监听器校验事件归属，防止多 manager 实例重复处理。 */
    override var owner: MenuManager? = null
```

`import com.github.mayblock.easylib.impl.bukkit.menu.MenuManager`（`:16`）**暂时保留** —— `:45` 的 `onDestroyed` KDoc 仍以 `[MenuManager]` 引用它。Task 2 删除该参数时一并移除此 import。

- [ ] **Step 8: 运行测试，确认转绿**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.*"
```
Expected: PASS

- [ ] **Step 9: 新增测试 —— `menus` 名册的摘除现在可观察了**

在 `MenuManagerChestTest.kt` 末尾（类的收尾 `}` 之前）追加。所需符号均已由现有 import 覆盖（`kotlin.test.*` 含 `assertNull`/`assertNotNull`；`MenuInteractionListener`/`MenuManager` 同包）。

```kotlin
    // ---- 名册（menus）的可观察面是 route()：destroy 后应被摘除 ----
    //
    // 不能用 hasActiveMenu/getViewers 断言此事：destroy() 第一步 view.closeAll() 即触发
    // InventoryCloseEvent → handleClose → MenuCloseEvent → activeMenus 清空，
    // 那条断言无论记账跑没跑都成立，是空断言。

    @Test
    fun `destroy 后菜单从名册摘除，不再被路由`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0, Material.DIAMOND) }
        } as RealChestMenu

        assertNotNull(mgr.route(menu), "创建后应在名册中")
        menu.destroy()
        assertNull(mgr.route(menu), "destroy 后应已摘除，否则 menus 只增不减")
    }
```

- [ ] **Step 10: 运行新测试**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.MenuManagerChestTest"
```
Expected: PASS。此时摘除仍由既有的 `onDestroyed = ::forget` 回调完成 —— 本测试是 Task 2 更换机制时的安全网，故此处**应当**直接通过。

- [ ] **Step 11: 全量构建**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 12: 暂存（不要提交）**

注意：`RealChestMenu.kt` 含一处维护者的尾逗号 WIP（`MenuEventDispatcher(),` → `MenuEventDispatcher()`），随本次一并暂存，**这是预期的**。

```bash
git add platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/BukkitMenu.kt \
        platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManager.kt \
        platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuInteractionListener.kt \
        platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt \
        platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuInteractionListenerTest.kt \
        platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManagerChestTest.kt
git status --short
```
报告输出。**不要 commit。**

---

### Task 2: 菜单侧 `onDestroyed` 回调改为 `MenuDestroyEvent`

**Files:**
- Modify: `common-api/src/main/kotlin/com/github/mayblock/easylib/api/util/Priority.kt:7-9`
- Modify: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/MenuEvent.kt`（末尾追加）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt:16,44-46,99-107`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManager.kt:42-50,55-69`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManagerChestTest.kt`（新增 2 个测试）

**Interfaces:**
- Consumes: `MenuManager.route(holder)`（Task 1 产出）—— 用于断言记账时序。
- Produces: `class MenuDestroyEvent(override val menu: Menu) : MenuEvent`（公开 API）。
- Produces: `Priority.MONITOR = Priority(Int.MAX_VALUE)`（公开 API）—— Task 3 也会用。

- [ ] **Step 1: 写失败测试**

在 `MenuManagerChestTest.kt` 末尾追加。import 区补：
```kotlin
import com.github.mayblock.easylib.api.bukkit.menu.MenuDestroyEvent
```

```kotlin
    @Test
    fun `destroy 派发 MenuDestroyEvent 给上游订阅者`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0, Material.DIAMOND) }
        } as RealChestMenu
        var destroys = 0
        menu.on { on<MenuDestroyEvent> { destroys++ } }

        menu.destroy()
        menu.destroy() // 幂等：destroyed 短路，不应重复派发

        assertEquals(1, destroys, "MenuDestroyEvent 应恰好派发一次")
    }

    @Test
    fun `manager 的记账在上游 destroy 处理器之后执行（名册仍完整）`() {
        val mgr = manager()
        val menu = mgr.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
            page(Component.text("t")) { slot(0, Material.DIAMOND) }
        } as RealChestMenu
        var inRegistryDuringHandler: Boolean? = null
        // 上游用默认优先级订阅。register() 的订阅发生在 createChestMenu 返回之前，
        // 故本监听必然晚于 manager 的监听插入；若 manager 用 Priority.DEFAULT 记账，
        // 稳定排序会让 manager 先跑，此处将读到 null。
        menu.on { on<MenuDestroyEvent> { inRegistryDuringHandler = mgr.route(menu) != null } }

        menu.destroy()

        assertEquals(true, inRegistryDuringHandler, "上游 destroy 处理器执行时菜单应仍在名册中：manager 记账须以 Priority.MONITOR 垫底")
        assertNull(mgr.route(menu), "记账最终仍须完成")
    }
```

- [ ] **Step 2: 运行测试，确认因编译失败而红**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.MenuManagerChestTest"
```
Expected: 编译失败，`Unresolved reference 'MenuDestroyEvent'`

- [ ] **Step 3: 新增 `Priority.MONITOR`**

`Priority.kt` 的 `companion object` 从：
```kotlin
    companion object {
        val DEFAULT = Priority(10)
    }
```
改为：
```kotlin
    companion object {
        val DEFAULT = Priority(10)

        /**
         * 最后执行、只观察不修改（语义同 Bukkit 的 `EventPriority.MONITOR`）。
         *
         * 事件按 priority 升序触发，故本值使监听排在最末。注意同优先级按订阅顺序，
         * 因此本值不构成「绝对最后」的强保证：[Priority] 构造器公开，任何订阅方
         * 都可自行传入等值或更大的数。
         */
        val MONITOR = Priority(Int.MAX_VALUE)
    }
```

- [ ] **Step 4: 新增 `MenuDestroyEvent`**

`MenuEvent.kt` 末尾追加：
```kotlin

/**
 * 菜单被销毁时派发（[Menu.destroy]）。派发时 [menu] 的 `isDestroyed` 已为 true，
 * 事件总线尚未拆除；本事件是订阅者做清理的最后时机，其后总线即被关闭。
 * 幂等：重复 destroy 不会重复派发。
 */
class MenuDestroyEvent(
    override val menu: Menu
): MenuEvent
```

- [ ] **Step 5: `RealChestMenu` —— 删除 `onDestroyed`，destroy 链改为派发事件**

import 区（`:16`）删除：
```kotlin
import com.github.mayblock.easylib.impl.bukkit.menu.MenuManager
```
import 区新增：
```kotlin
import com.github.mayblock.easylib.api.bukkit.menu.MenuDestroyEvent
```

构造参数（`:44-46`）从：
```kotlin
    private val hidePlayerInventory: Boolean = true,
    /** destroy() 末尾回调，供 [MenuManager] 撤销登记（避免 menus 只增不减）。 */
    private val onDestroyed: (RealChestMenu) -> Unit = {},
    private val dispatcher: MenuEventDispatcher = MenuEventDispatcher()
```
改为：
```kotlin
    private val hidePlayerInventory: Boolean = true,
    private val dispatcher: MenuEventDispatcher = MenuEventDispatcher()
```

`destroy()`（`:99-107`）从：
```kotlin
    override fun destroy() {
        if (destroyed) return
        view.closeAll() // 快照遍历防 CME（见 RealChestView.closeAll）
        updateLoop.stop()
        hideMask?.dispose()
        dispatcher.close()
        destroyed = true
        onDestroyed(this)
    }
```
改为：
```kotlin
    /**
     * 顺序契约：先置 [destroyed]，再派发 [MenuDestroyEvent]，最后才关总线。
     * - 置位早于派发：订阅者看到的是一致状态（此时 open() 会正确 check 失败）。
     * - 派发早于 close()：close() 会 unsubscribeAll，之后派发无人收听。
     * - closeAll() 早于置位：它会触发 InventoryCloseEvent → handleClose → MenuCloseEvent，
     *   语义上属于「销毁前的正常关窗」，顺序正确。
     */
    override fun destroy() {
        if (destroyed) return
        view.closeAll() // 快照遍历防 CME（见 RealChestView.closeAll）
        updateLoop.stop()
        hideMask?.dispose()
        destroyed = true
        dispatcher.publish(MenuDestroyEvent(this))
        dispatcher.close()
    }
```

- [ ] **Step 6: `MenuManager` —— 记账并入既有订阅块，删除 `forget`**

import 区新增（`com.github.mayblock.easylib.api.bukkit.menu.*` 已覆盖 `MenuDestroyEvent`）：
```kotlin
import com.github.mayblock.easylib.api.util.Priority
```

`createChestMenu` 的工厂 lambda 从：
```kotlin
            RealChestMenu(
                taskScheduler,
                packetManager,
                title,
                type,
                slots,
                hidePlayerInventory,
                onDestroyed = ::forget
            ).also(::register)
```
改为：
```kotlin
            RealChestMenu(
                taskScheduler,
                packetManager,
                title,
                type,
                slots,
                hidePlayerInventory
            ).also(::register)
```

`register` 的订阅块（Task 1 落地后的版本）从：
```kotlin
        menu.on {
            on<MenuOpenEvent> { activeMenus[player] = menu }
            on<MenuCloseEvent> { if (activeMenus[player] === menu) activeMenus.remove(player) }
        }
```
改为：
```kotlin
        menu.on {
            on<MenuOpenEvent> { activeMenus[player] = menu }
            on<MenuCloseEvent> { if (activeMenus[player] === menu) activeMenus.remove(player) }
            // MONITOR 垫底：上游的 destroy 处理器须先跑完（届时名册仍完整、getViewers 仍可用），
            // 记账最后做。用 DEFAULT 会因「register 的订阅早于上游插入 + 稳定排序」而抢先执行。
            on<MenuDestroyEvent>(Priority.MONITOR) {
                menus.remove(menu)
                activeMenus.entries.removeIf { it.value === menu }
            }
        }
```

删除 `forget` 私有方法：
```kotlin
    /** 菜单 destroy() 时的回调：撤销登记，避免 [menus]/[activeMenus] 只增不减地累积已销毁的菜单。 */
    private fun forget(menu: Menu) {
        menus.remove(menu)
        activeMenus.entries.removeIf { it.value === menu }
    }
```

`close()` 的注释从：
```kotlin
        // destroy() 会经 onDestroyed 回调触发 forget()，进而修改 menus 本身；
        // 必须遍历快照，否则会在 forEach 过程中并发结构性修改 menus 导致 CME。
```
改为（**`menus.toList()` 快照本身必须保留** —— 同步性与「会修改 menus」两点均未变）：
```kotlin
        // destroy() 会同步派发 MenuDestroyEvent，其 MONITOR 监听会修改 menus 本身；
        // 必须遍历快照，否则会在 forEach 过程中并发结构性修改 menus 导致 CME。
```

- [ ] **Step 7: 运行测试，确认转绿**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.*"
```
Expected: PASS（含 Task 1 Step 9 的名册摘除测试 —— 它现在由事件链而非回调驱动）

- [ ] **Step 8: 变异测试 —— 证明 MONITOR 断言非空**

把 `MenuManager.register` 中的 `Priority.MONITOR` 临时改为 `Priority.DEFAULT`，重跑：

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.menu.MenuManagerChestTest"
```
Expected: FAIL，`manager 的记账在上游 destroy 处理器之后执行（名册仍完整）` 报 "上游 destroy 处理器执行时菜单应仍在名册中"。

**确认失败后改回 `Priority.MONITOR`，重跑确认转绿。** 若变异体未导致失败，说明该断言是空的，停下来报告。

- [ ] **Step 9: 全量构建**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: 暂存（不要提交）**

```bash
git add common-api/src/main/kotlin/com/github/mayblock/easylib/api/util/Priority.kt \
        platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/menu/MenuEvent.kt \
        platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManager.kt \
        platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt \
        platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/MenuManagerChestTest.kt
git status --short
```
报告输出。**不要 commit。**

---

### Task 3: 覆盖层侧对称化 —— `onDestroyed` 改为 `OverlayDestroyEvent`

**Files:**
- Modify: `platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/overlay/slot/event/OverlayEvent.kt`（末尾追加）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/PlayerOverlayImpl.kt:46-47,117-129`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayManager.kt:30-49`
- Test: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/PlayerOverlayImplDestroyTest.kt`（新建）

**Interfaces:**
- Consumes: `Priority.MONITOR`（Task 2 产出）。
- Produces: `class OverlayDestroyEvent(override val overlay: PlayerOverlay) : OverlayEvent`（公开 API）。
- Produces: `OverlayManager.track` 不再存在（内联进 `create()`）。

**背景：** `PlayerOverlayImpl.onDestroyed`（`:47`，`internal var (() -> Unit)?`）与菜单侧的 `RealChestMenu.onDestroyed` 是同一类侵入式设计 —— 在生产对象上挂回调槽供持有者写入。维护者要求删除。它删不掉的原因是缺替代机制（直接删则 `overlays` 只增不减）。本任务提供替代机制。

**注意本任务无法测到的部分：** `OverlayManager` 对外零可观察面（不像 `MenuManager` 有 `MenuRegistry`/`route`），因此「`overlays` 名册是否被正确摘除」**仍然无法断言**，本任务不为此新造 API。可测的只有 `PlayerOverlayImpl` 是否正确派发事件。这是已知且经维护者接受的缺口。

- [ ] **Step 1: 写失败测试（新建文件）**

创建 `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/PlayerOverlayImplDestroyTest.kt`。

注意：本测试须自带一个不触碰 PacketEvents 的 `OverlayTransport` 假实现（与 Task 0 删掉的 `NoopTransport` 同因 —— 真实的 `PacketOverlayTransport` 会触发 PacketEvents 单例，单测跑不了）。这不是把删掉的测试搬回来：那个测的是 `OverlayManager` 的名册（不可观察），这个测的是 `PlayerOverlayImpl` 的事件派发（可观察）。

```kotlin
package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlayDestroyEvent
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.overlay.slot.SlotMap
import com.github.mayblock.easylib.impl.bukkit.overlay.transport.OverlayTransport
import io.mockk.mockk
import org.bukkit.entity.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 不触碰 PacketEvents 的最小 [OverlayTransport] 假实现（真实 PacketOverlayTransport 依赖 PacketEvents 单例）。 */
private class NoopTransport : OverlayTransport {
    override fun paintAll(player: Player) {}
    override fun paint(player: Player, slot: Int) {}
    override fun restore(player: Player) {}
    override fun attach(callbacks: OverlayTransport.Callbacks): Disposable = Disposable {}
}

private fun overlay(): PlayerOverlayImpl {
    val map = SlotMap(emptyMap())
    return PlayerOverlayImpl(
        emptyMap(),
        map,
        mockk<TaskScheduler>(relaxed = true),
        mockk<TaskExecutor>(relaxed = true),
        NoopTransport(),
    )
}

class PlayerOverlayImplDestroyTest {

    @Test
    fun `destroy 派发 OverlayDestroyEvent 给订阅者`() {
        val o = overlay()
        var destroys = 0
        o.on { on<OverlayDestroyEvent> { destroys++ } }

        o.destroy()
        o.destroy() // 幂等：isDestroyed 短路，不应重复派发

        assertEquals(1, destroys, "OverlayDestroyEvent 应恰好派发一次")
    }

    @Test
    fun `派发 OverlayDestroyEvent 时 isDestroyed 已置位（订阅者看到一致状态）`() {
        val o = overlay()
        var seenDestroyed: Boolean? = null
        o.on { on<OverlayDestroyEvent> { seenDestroyed = overlay.isDestroyed } }

        o.destroy()

        assertTrue(seenDestroyed == true, "派发须晚于 isDestroyed = true；否则订阅者读到不一致状态")
    }
}
```

- [ ] **Step 2: 运行测试，确认因编译失败而红**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.overlay.PlayerOverlayImplDestroyTest"
```
Expected: 编译失败，`Unresolved reference 'OverlayDestroyEvent'`

- [ ] **Step 3: 新增 `OverlayDestroyEvent`**

`OverlayEvent.kt` 末尾追加：
```kotlin

/**
 * 覆盖层被销毁时派发（[PlayerOverlay.destroy]）。派发时 [overlay] 的 `isDestroyed` 已为 true，
 * 事件总线尚未拆除；本事件是订阅者做清理的最后时机，其后总线即被关闭。
 * 幂等：重复 destroy 不会重复派发。
 */
class OverlayDestroyEvent(
    override val overlay: PlayerOverlay,
) : OverlayEvent
```

- [ ] **Step 4: `PlayerOverlayImpl` —— 删除 `onDestroyed`，destroy 链改为派发事件**

import 区新增：
```kotlin
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlayDestroyEvent
```

删除 `:46-47`：
```kotlin
    /** 覆盖层销毁时的清理钩子（由持有者，如 [OverlayManager]，挂接以停止追踪本实例）。 */
    internal var onDestroyed: (() -> Unit)? = null
```

`destroy()`（`:117-129`）从：
```kotlin
    override fun destroy() {
        if (isDestroyed) return
        viewers.snapshot().forEach { player ->
            removeViewer(player)
            transport.restore(player)
        }
        updateLoop.stop()
        transportSub?.dispose()
        dispatcher.close()
        viewers.clear()
        isDestroyed = true
        onDestroyed?.invoke()
    }
```
改为（**注意 `dispatcher.close()` 从原位置下移到末尾** —— 原顺序 close 早于置位，无法派发）：
```kotlin
    /**
     * 顺序契约（与菜单侧 [com.github.mayblock.easylib.impl.bukkit.menu.type.chest.RealChestMenu.destroy] 一致）：
     * 先置 [isDestroyed]，再派发 [OverlayDestroyEvent]，最后才关总线。
     * - 置位早于派发：订阅者看到的是一致状态（此时 show/hide 会正确 check 失败）。
     * - 派发早于 close()：close() 会 unsubscribeAll，之后派发无人收听。
     * - removeViewer 循环早于置位：它派发 [OverlayHideEvent]，语义上属于「销毁前的正常关闭」。
     */
    override fun destroy() {
        if (isDestroyed) return
        viewers.snapshot().forEach { player ->
            removeViewer(player)
            transport.restore(player)
        }
        updateLoop.stop()
        transportSub?.dispose()
        viewers.clear()
        isDestroyed = true
        dispatcher.publish(OverlayDestroyEvent(this))
        dispatcher.close()
    }
```

- [ ] **Step 5: `OverlayManager` —— `track()` 内联进 `create()` 并删除**

import 区新增：
```kotlin
import com.github.mayblock.easylib.api.bukkit.overlay.slot.event.OverlayDestroyEvent
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.util.Priority
```

`:30-49`（`create()` + `track()`）从：
```kotlin
    override fun create(block: PlayerOverlayScope.() -> Unit): PlayerOverlay =
        PlayerOverlayBuilder { slots ->
            val map = SlotMap(slots)
            PlayerOverlayImpl(slots, map, taskScheduler, BukkitAsyncExecutor(plugin), PacketOverlayTransport(map))
        }
            .apply(block)
            .build()
            .let { it as PlayerOverlayImpl }
            .let(::track)

    /**
     * 登记一个覆盖层实例并挂接销毁回调，销毁时自动从 [overlays] 摘除（防泄漏链）。
     * 抽出为独立函数：既是 [create] 的实现，也便于测试直接注入假实现验证摘除逻辑
     * （真实的 [PlayerOverlayImpl] 依赖 PacketEvents 单例，单测环境下无法构造）。
     */
    internal fun track(overlay: PlayerOverlayImpl): PlayerOverlay {
        overlays.add(overlay)
        overlay.onDestroyed = { overlays.remove(overlay) }
        return overlay
    }
```
改为（`track()` 整个删除；其 KDoc 的第二条理由「便于测试直接注入假实现」已随该测试删除而失效，且只剩 `create()` 一个调用者）：
```kotlin
    override fun create(block: PlayerOverlayScope.() -> Unit): PlayerOverlay =
        PlayerOverlayBuilder { slots ->
            val map = SlotMap(slots)
            PlayerOverlayImpl(slots, map, taskScheduler, BukkitAsyncExecutor(plugin), PacketOverlayTransport(map))
        }
            .apply(block)
            .build()
            .let { it as PlayerOverlayImpl }
            .also { overlay ->
                overlays.add(overlay)
                // MONITOR 垫底，与菜单侧记账一致：上游的 destroy 订阅者先跑完，记账最后做。
                overlay.on { on<OverlayDestroyEvent>(Priority.MONITOR) { overlays.remove(overlay) } }
            }
```

- [ ] **Step 6: 运行测试，确认转绿**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew :platform-bukkit-impl:test --tests "com.github.mayblock.easylib.impl.bukkit.overlay.*"
```
Expected: PASS

- [ ] **Step 7: 全仓库确认 `onDestroyed` 已彻底消失**

```bash
grep -rn "onDestroyed" --include="*.kt" . || echo "CLEAN: no onDestroyed remains"
```
Expected: `CLEAN: no onDestroyed remains`。若仍有命中，报告命中位置 —— 说明有遗漏。

- [ ] **Step 8: 全量构建**

```bash
export JAVA_HOME="D:/Program Files/Zulu/zulu-25"
./gradlew build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 暂存（不要提交）**

```bash
git add platform-bukkit-api/src/main/kotlin/com/github/mayblock/easylib/api/bukkit/overlay/slot/event/OverlayEvent.kt \
        platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/PlayerOverlayImpl.kt \
        platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/OverlayManager.kt \
        platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/PlayerOverlayImplDestroyTest.kt
git status --short
```
报告输出。**不要 commit。**

---

## 收尾

四个 Task 完成后，以下事项**明确不在本次范围**，不要顺手做：

- `AbstractBukkitMenu` 基类（见设计文档 §2.2）
- `RealChestMenu.kt` / `PlayerOverlayImpl.kt` 的 `dispatcher` 死默认参数（无人覆盖的 seam）
- `overlay/slot/SlotUpdateLoop.kt:11` 的过时 KDoc（「保持异步」）
- `OverlayManager` 的 async 决策（`BukkitAsyncExecutor` 注入点）无测试覆盖的问题
- 为 `OverlayManager` 新造注册表 API 以便测试名册摘除 —— **明确否决**，不为测试造 API
