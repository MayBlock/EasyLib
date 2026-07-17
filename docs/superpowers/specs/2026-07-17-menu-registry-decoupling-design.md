# 菜单注册表解耦设计（owner / onDestroyed）

日期：2026-07-17
状态：已定稿，待实施
相关：`docs/superpowers/plans/2026-07-05-chest-menu-real-container.md`

## 1. 问题

`MenuManager` 与 `BukkitMenu` 之间存在两处耦合，性质不同但病根一致：**注册表的记账，是通过「往菜单对象身上写东西」实现的，而不是由注册表自己拥有。**

### 1.1 `owner`：结构性循环依赖

`BukkitMenu.kt:24` 的 `var owner: MenuManager?` 让抽象反过来依赖具体类：`BukkitMenu`（接口）→ `MenuManager`（class），而 `MenuManager.kt:57` 又 → `BukkitMenu`，构成环。

该字段的 KDoc 自己记录了这笔账：声明为 `var` 而非 `val` 是刻意取舍，因为 `val` 会迫使 `register()` 向下转型到具体实现类 —— 「恰是本次解耦要消除的耦合」。即：上一轮重构消掉了向下转型，代价是留下了环，并引入了一个谁都能写的可变字段（`menu.owner = 别的 manager` 无人拦截）。

附带成本：每个新 UI 类型（铁砧 / 熔炉 / ……）都必须抄一行 `override var owner: MenuManager? = null`，而这与「你是一个菜单」毫无关系。

**根因**：`MenuInteractionListener.kt:27` 问的 `it.owner === owner`，本质是「这个菜单归不归我管」—— 这是注册表的问题，不是菜单的问题。而 `MenuManager.kt:24` 已经持有 `menus` 这份名册了。知识存错了地方。

### 1.2 `onDestroyed`：未强制的契约 + 双套 idiom

`RealChestMenu.kt:46` 的 `onDestroyed` 是构造参数，**不在 `BukkitMenu` 接口上**。它是「每个实现自觉遵守的约定」，不是契约：新 UI 类型的作者没有任何信号提示他必须接收此参数、必须在 `destroy()` 末尾调用它 —— 忘了就是 `menus` / `activeMenus` 静默泄漏，编译器不吭声。

同时它与 overlay 侧不是一套写法：`PlayerOverlayImpl.kt:47` 用 `internal var onDestroyed: (() -> Unit)?`，`RealChestMenu` 用构造参数。同一仓库、同一件事、两种 idiom。

## 2. 决策

### 2.1 A1 —— 路由反转，删除 `owner`

把「这菜单归不归我」还给注册表自己回答。

```kotlin
// BukkitMenu.kt —— owner 整个删除，环随之断开
internal interface BukkitMenu : Menu, InventoryHolder {
    fun handleOpen(player: Player)
    fun handleClick(e: InventoryClickEvent)
    fun handleDrag(e: InventoryDragEvent)
    fun handleClose(player: Player)
    fun getItem(index: Int): ItemStack?
    fun setItem(index: Int, item: ItemStack?)
}

// MenuManager.kt
private val menus = Collections.newSetFromMap(IdentityHashMap<Menu, Boolean>())

internal fun route(holder: InventoryHolder?): BukkitMenu? =
    (holder as? BukkitMenu)?.takeIf { it in menus }

// MenuInteractionListener.kt —— 仍持有 owner: MenuManager，但改为委托查询
private fun route(holder: InventoryHolder?): BukkitMenu? = owner.route(holder)
```

**收益**：`BukkitMenu → MenuManager` 的环消失；新 UI 类型不再背记账字段；可写全局态消失；归属判定从「对象自报家门」变为「注册表查名册」，多 manager 校验语义更硬。

**`menus` 容器变更说明**：`mutableListOf` → `IdentityHashMap` 背书的 set。这不是为路由性能，而是**修正既有的语义不一致**：`activeMenus.entries.removeIf { it.value === menu }`（`MenuManager.kt:68`）与 `getViewers` 的 `it === menu`（`:32`）本来就用身份语义，只有 `menus` 走 `List.remove()` 的 equals 路径。统一为身份语义，顺带把 `contains` 变成 O(1)。

**`register` 可见性：`private` → `internal`（实施期发现，非原设计预见）**

`MenuInteractionListenerTest.kt:42,68,89,101` 的 `FakeHopperMenu` 靠 `.apply { owner = mgr }` 构造归属关系 —— `BukkitMenu.owner` 的原始 KDoc 明写「直接构造的测试实例可手动赋值」。即：**`owner` 是可写 `var`，部分原因正是为了让测试能表达归属。** 删掉它后，测试需要另一条路把任意 `BukkitMenu` 注册进 manager。

裁定：`register` 改为 `internal`。与先前对 `OverlayManager.track()` 的审计裁定一致 —— 它**不是 test-only 成员**（真实生产调用者在 `MenuManager.kt:50` 的 `createChestMenu`），且 Kotlin `internal` 为模块级，上游不可见。收益：测试改走**真实注册路径**，比手动戳字段更忠实。

**接受的语义差异**：`owner` 是单值的（`menu.owner = a; menu.owner = b` → 仅 b 生效）；改为 set 后，同一菜单在理论上可同时存在于两个 manager 的名册中，届时两个监听器都会路由 → 重复处理。生产上不可达：`register` 仅由 `createChestMenu` 调用，每个菜单只由一个 manager 创建。`owner` 单值性提供的是针对不可达场景的意外保护，不视为回归。

**已否决的备选（A2）**：`owner` 保留但改为接口类型（`MenuOwner`）。断了具体类的环，但字段仍挂在每个菜单身上 —— 治标不治本。

### 2.2 B1 —— `MenuDestroyEvent` 走已有总线，删除 `onDestroyed`

关键使能事实：`MenuEventDispatcher.kt:37` 的 `publish` 就是 `bus.emit(event)`，**同步派发**（其 KDoc 明载 menu 事件全部产生于主线程，故无 overlay 那个 `publishOnMainThread`）；且 `register()`（`MenuManager.kt:58-61`）**本来就在订阅这条总线**。

```kotlin
// platform-bukkit-api/.../menu/MenuEvent.kt —— 新增（上游目前根本没有 destroy 事件可订阅）
class MenuDestroyEvent(override val menu: Menu) : MenuEvent

// RealChestMenu.kt —— onDestroyed 构造参数删除
override fun destroy() {
    if (destroyed) return
    view.closeAll()
    updateLoop.stop()
    hideMask?.dispose()
    destroyed = true                              // 先置位
    dispatcher.publish(MenuDestroyEvent(this))    // 必须早于 close()
    dispatcher.close()
}

// MenuManager.register() —— forget() 私有方法删除，记账并入既有订阅块
menu.on {
    on<MenuOpenEvent> { activeMenus[player] = menu }
    on<MenuCloseEvent> { if (activeMenus[player] === menu) activeMenus.remove(player) }
    on<MenuDestroyEvent>(Priority.MONITOR) {
        menus.remove(menu)
        activeMenus.entries.removeIf { it.value === menu }
    }
}
```

**收益**：复用既有 seam，不引入新机制；`createChestMenu` 里的 `onDestroyed = ::forget` 参数消失；顺带给上游补上一个他们目前完全没有的 `MenuDestroyEvent`（纯 API 增益，契合「为调用方设计」）。

**诚实的保留**：契约仍未被编译器强制 —— 新 UI 类型忘了 publish `MenuDestroyEvent` 照样静默泄漏。但比现状好在：这是**公开 API 上有文档的事件契约**，而非藏在 impl 构造器里的私有 lambda 参数。若要真正强制，须引入 `AbstractBukkitMenu` 基类（B3），而这与 `RealChestMenu` KDoc 明写的「对标 overlay 的组合切分」相悖 —— 不为此换掉组合。

### 2.3 优先级：`Priority.MONITOR`，记账垫底

**为什么垫底**：由维护者明确要求 —— 框架的记账应当在所有 destroy 订阅者之后执行。

**⚠️ 本节初稿给出的理由是错的，最终审查中被推翻，特此留档以免重蹈：** 初稿称「若记账先跑，上游在 destroy 处理器里调 `manager.getViewers(menu)` 会拿到空集」。**不成立。** `MenuRegistry` 对上游暴露的 `getActiveMenu` / `hasActiveMenu` / `getViewers` **三者只读 `activeMenus`**，而 `activeMenus` 早在 `destroy()` 首步 `view.closeAll()` → `InventoryCloseEvent` → `handleClose` → `MenuCloseEvent` 时就已被清空 —— 上游**无论记账是否垫底都拿到空集**。

MONITOR 实际保住的只有 `menus` 名册，而它仅经 `internal` 的 `route()` 可见，**对上游不可观察**。故本决策当前不产生任何上游可见的收益。

**仍然保留 MONITOR 的理由**（诚实版本）：
- **原则性**：框架记账最后做，是正确的缺省姿态，代价为零。
- **前瞻性**：若 `MenuRegistry` 将来暴露读 `menus` 的 API（如 `getAllMenus()`），时序已经是对的，不必届时再回来改。

**推论**：§4 的时序断言守的是「框架记账最后做」这条**内部契约**，而非任何上游可观察行为。这不降低它的价值（内部契约同样会被回归破坏），但不要把它宣传成上游收益。

**机制核实**：`SimpleEventBus.kt:28` 用 `listeners.sortBy { it.priority }` 升序维护，`emit`（`:41`）顺序触发 → **数值越大越晚执行**。既有测试注释印证方向（`RealChestMenuClickTest.kt:287-288`：`Priority(20)` 标「后执行」、`Priority(1)` 标「先执行」）。`EventScope.on(priority = Priority.DEFAULT, handler)`（`Event.kt:45`）现成可用。

**为什么不能只「给个大点的数」**：`sortBy` 是稳定排序 → 同优先级按订阅顺序。而 `register()` 的订阅发生在 `createChestMenu` 把菜单返回给调用方**之前**，因此同值并列时 manager 永远排在上游前面 —— 恰是要避免的一侧。必须取一个上游拿不到的更大值。

```kotlin
// Priority.kt —— 当前只有 DEFAULT = Priority(10)，无 MONITOR/LOWEST 等命名常量
companion object {
    val DEFAULT = Priority(10)
    /** 最后执行、只观察不修改（语义同 Bukkit EventPriority.MONITOR）。 */
    val MONITOR = Priority(Int.MAX_VALUE)
}
```

**已否决的备选（P2）**：分两档 —— `MONITOR = Priority(Int.MAX_VALUE - 1)` 给上游、`LAST = Priority(Int.MAX_VALUE)` 给框架记账。理由：`Priority` 是 `data class Priority(val priority: Int)`，构造器公开，上游任何时候都能直接写 `Priority(Int.MAX_VALUE)` —— **任何常量分档都挡不住，P2 只是把「靠约定」包装得更像「靠机制」，买到的安全边际很薄**。且 Kotlin 的 `internal` 按模块划分，common-api 的 internal 在 platform-bukkit-impl 不可见，`LAST` 只能 public + KDoc 约束，进一步削弱了它的说服力。

接受的残余风险：上游若显式使用 `Priority.MONITOR`，与 manager 并列 → manager 先跑。属病态用法。

### 2.4 destroy 链的完整顺序契约

```
view.closeAll()  →  updateLoop.stop()  →  hideMask.dispose()
  →  destroyed = true                     // 订阅者看到一致状态；此时 open() 会正确 check 失败
  →  上游 on<MenuDestroyEvent> handler    // 名册完整，getViewers/hasActiveMenu 可用
  →  manager on<MenuDestroyEvent>(MONITOR) // 记账摘除
  →  dispatcher.close()                    // 总线拆除
```

注：`view.closeAll()` 会触发 `InventoryCloseEvent` → `handleClose` → `publish(MenuCloseEvent)`，发生在 `destroyed = true` 之前，语义正确，不受影响。

`MenuManager.close()`（`:71-78`）遍历快照防 CME 的注释依然成立：触发链从「回调」变成「同步事件」，但同步性与「会修改 `menus`」这两点都没变。

## 3. 上游兼容性（CLAUDE.md 约束）

**本次改动不删除任何公开符号，公开面纯增量：**

| 符号 | 可见性 | 影响 |
|---|---|---|
| `BukkitMenu.owner` | `internal`（`BukkitMenu` 本身即 internal） | 删除 —— **不是上游破坏性变更** |
| `RealChestMenu.onDestroyed` | `internal class` 的 `private` 构造参数 | 删除 —— 上游不可见 |
| `MenuManager.forget` | `private` | 删除 —— 上游不可见 |
| `MenuManager.route` | 新增 `internal` | 上游不可见 |
| `MenuManager.register` | `private` → `internal` | 仍为模块内可见，上游不可见 |
| `MenuDestroyEvent` | 新增 `public` | **纯增益**：上游首次获得 destroy 事件 |
| `Priority.MONITOR` | 新增 `public` | **纯增益**：对所有事件类型（arena / overlay / menu / slot）可用 |
| `PlayerOverlayImpl.onDestroyed` | `internal`（类本身即 internal） | 删除 —— 上游不可见 |
| `OverlayManager.track` | `internal` | 内联进 `create()` 后删除 —— 上游不可见 |
| `OverlayManager.trackedCount` | `internal` | 删除 —— 上游不可见（见 §6） |
| `OverlayDestroyEvent` | 新增 `public` | **纯增益**：上游首次获得覆盖层销毁事件 |

## 4. 测试策略

**不能用 `MenuRegistry` API 守卫 `menus` 的摘除 —— 那是空断言。** `destroy()` 的第一步 `view.closeAll()` 即触发 `InventoryCloseEvent → handleClose → publish(MenuCloseEvent)` → `activeMenus` 被清空。因此「destroy 后 `hasActiveMenu` 为 false / `getViewers` 为空」**无论 `forget` 跑没跑都成立**，测不到任何东西。

**`menus` 集合的唯一可观察面是 `route()`** —— 而 `route()` 正是 A1 引入的。这构成了 A1 与 B1 的实施顺序依赖：**A1 必须先做**，因为在 A1 之前 `menus` 只被 `close()` 读取，从外部完全不可观察（这也正是它此前无测试覆盖的原因）。

```kotlin
// A1 落地后即可写：非侵入（route 有真实生产调用者，且为 internal，上游不可见）
menu.destroy()
assertNull(mgr.route(menu))
```

**`Priority.MONITOR` 的唯一守卫**：订阅者在 `on<MenuDestroyEvent>` 中读到的 `mgr.route(menu)` 必须**非空**（证明记账确实垫底）。注意 `route()` 是 `internal` —— 该断言守的是内部契约，不是上游可观察行为（见 2.3 的更正）。

该断言可杀死变异体 `Priority.MONITOR → Priority.DEFAULT`：`register()` 的订阅发生在 `createChestMenu` 返回之前，故上游的 DEFAULT 监听必然晚于 manager 的 DEFAULT 监听插入；`sortBy` 稳定排序 → manager 先跑 → 上游 handler 读到 `route(menu) == null` → 断言失败。**缺了这条断言，整个 2.3 节等于没实现。**

### 2.5 overlay 侧对称化（范围变更：由维护者要求纳入本次）

**原定不做，后经维护者明确要求纳入**：`PlayerOverlayImpl.onDestroyed`（`:47`）属于同一类侵入式设计 —— 在生产对象上挂一个回调槽供持有者写入。维护者曾尝试直接删除，但删不掉：删了 `overlay.onDestroyed = { overlays.remove(overlay) }` 后 `overlays` 只增不减。它删不掉的原因正是缺替代机制。

overlay 侧结构与菜单侧完全对称，照搬 B1 即可：`OverlayEvent`（根类型，`OverlayEvent.kt:8-10`）+ `OverlayShowEvent` / `OverlayHideEvent` 已在；`OverlayEventDispatcher.kt:37` 的 `publish` 同为同步 `bus.emit`。

```kotlin
// OverlayEvent.kt
class OverlayDestroyEvent(override val overlay: PlayerOverlay) : OverlayEvent

// PlayerOverlayImpl.destroy() —— 删除 internal var onDestroyed
override fun destroy() {
    if (isDestroyed) return
    viewers.snapshot().forEach { player -> removeViewer(player); transport.restore(player) }
    updateLoop.stop()
    transportSub?.dispose()
    viewers.clear()
    isDestroyed = true
    dispatcher.publish(OverlayDestroyEvent(this))
    dispatcher.close()
}

// OverlayManager.create() —— track() 内联并删除
        .let { it as PlayerOverlayImpl }
        .also { overlay ->
            overlays.add(overlay)
            overlay.on { on<OverlayDestroyEvent>(Priority.MONITOR) { overlays.remove(overlay) } }
        }
```

**顺序修正（必须）**：现状 `dispatcher.close()`（`:125`）**早于** `isDestroyed = true`（`:127`）。派发事件要求反过来 —— 与 2.4 的菜单侧顺序契约同理。`destroy()` 开头的 `removeViewer` 循环会派发 `OverlayHideEvent`，须保持在 `close()` 之前（现状即如此，不受影响）。

**`track()` 内联**：测试删除后（§6），`track()` 的 KDoc 第二条理由（「便于测试直接注入假实现」）失效，且只剩 `create()` 一个调用者 → 内联并删除。`internal` 成员，上游不可见。

**`Priority.MONITOR` 的使用**：overlay 侧目前**没有**可观察的注册表 API，因此严格说没有「让上游先跑」的现实需求。此处使用 MONITOR 的理由是与菜单侧保持一致，且若将来 `OverlayManager` 获得注册表 API，时序已经是对的。属于低成本对称性选择，非 YAGNI 违背。

**明确不会带来的收益**：本改动**不会**让 §6 删掉的测试复活。`OverlayManager` 依然零可观察面，`overlays` 的摘除从外部仍不可见。换事件是为消除侵入式回调槽，不是为找回覆盖率。

## 5. 明确不在本次范围

- **`AbstractBukkitMenu` 基类**（B3）：见 2.2 的理由。
- **`RealChestMenu.kt:47` / `PlayerOverlayImpl.kt:39` 的 `dispatcher` 默认参数**（无人覆盖的死 seam）：独立议题。

## 6. 附带清理：移除侵入式测试

删除 `OverlayManagerTest.kt` 的 `overlay destroy 后 manager 不再持有`（`:49-57`），及随之孤儿化的 `OverlayManager.trackedCount`（`:25-26`）。

**理由**：`trackedCount` 生产侧零调用者，纯为一句断言而存在于 main source —— 按 CLAUDE.md，暴露面应由上游需求正当化，而非被测试倒逼。且该测试的 KDoc 自承 `create()` 在单测跑不了（PacketEvents 单例），故它手工构造 `PlayerOverlayImpl` 再直接调 `track()` —— **测的是 `track()` 的接线，不是真实路径**，覆盖本就是残的。泄漏本身有界：`OverlayManager` 是 per-plugin 长生命周期。

**明确丢掉的覆盖**：`OverlayManager.kt:50` 的 `overlay.onDestroyed = { overlays.remove(overlay) }` 若被误删，测试全绿。此后仅由 spec §6 的人工验证兜底。

**根因备忘**：之所以只能侵入式写，是因为 `OverlayManager` 对外零可观察面 —— 对比 `MenuManager` 实现了 `MenuRegistry`（`getActiveMenu` / `hasActiveMenu` / `getViewers`），菜单侧的记账天然可观察（见 §4）。若将来上游确实需要 overlay 注册表 API，此测试可零侵入地回归。**现在不为测试造 API。**

**连带删除**（删掉该测试后即无引用）：`NoopTransport`（`:16-21`）、`fakeOverlay`（`:23-29`）、以及随之未使用的 import（`TaskExecutor`、`Disposable`、`SlotMap`、`OverlayTransport`、`Player`）。
