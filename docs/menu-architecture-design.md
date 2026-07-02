# Menu 子系统架构重设计（Spec）

> 状态：待评审 · 目标模块：`platform-bukkit-api` / `platform-bukkit-impl` / `common-api`
> 关联：菜单系统当前处于「`MenuApi`→`MenuFactory`/`MenuRegistry`、`Slot` 重构为 `EventBus<SlotEvent>`」的半完成状态，核心实现（`VirtualChestMenu`、`VirtualPlayerInventoryMenu`、`SlotUpdateScheduler`）不可编译。

## 1. 背景与现状

菜单子系统**纯粹依赖数据包**实现虚拟 GUI（不创建真实 Bukkit 容器）：箱子菜单（`ChestMenu`）发送 OpenWindow/WindowItems 等包并拦截客户端点击；玩家背包菜单（`PlayerInventoryMenu`）则覆盖玩家自身背包，拦截 WINDOW_ITEMS/SET_SLOT 并处理点击/交互/丢弃。

一个槽（slot）有三种关注点：展示的物品、点击行为、按计时器周期刷新物品的更新行为。用户通过 DSL 声明：

```kotlin
factory.createChestMenu(GENERIC_9X3) {
    page("标题") {
        slot(13, Material.DIAMOND) { onClick { player.sendMessage("clicked $type") } }
        slot(15, Material.CLOCK)   { onUpdate(trigger = Interval(1.seconds)) { item = ... } }
    }
}
```

当前重构把 `Slot` 定义成 `EventBus<SlotEvent> + val item`，试图把点击与更新统一成「每个 slot 一条事件总线上的事件」。

## 2. 现存问题

1. **声明与运行态被揉进同一个 `Slot`。** 「用户声明了什么」（item + handlers，不可变）和「运行期的活对象」（可变当前 item、包物品缓存、更新计时任务、点击派发）是两类不同的东西，被塞进一个 `Slot`。这逼出了 `val`→`var` 覆写、`as ManagedSlot` 向下转型等别扭手法。
2. **用「每个 slot 一条 EventBus」做派发——工具与粒度都不对。** 广播式总线无法表达「更新」：更新是*每个监听器各自按 trigger 周期重算*，不是广播事件——这正是 `SlotBuilder.onUpdate` 把 `trigger` 丢掉、调度器跑不起来的根因。而且给每个 slot 配一条 `CopyOnWriteArrayList`+优先级排序+类型过滤的总线，对「通常只有一个点击处理器」的场景过重。同时 `SlotClickEvent` 本就是 `MenuEvent`——总线的正确归属是**菜单**，不是 slot。
3. **两个 `VirtualMenu` 各自重复约 100 行** 观察者集合 / 包监听注册 / 更新调度 / 物品同步 plumbing；真正不同的只有 windowId、open/activate 语义、包→点击事件映射。

## 3. 目标 / 非目标

**目标**：高内聚、低耦合地重写 menu 运行态；声明与运行态分离；事件总线落到菜单粒度并复用项目既有事件系统；更新建模为「定时规则」；抽出两个菜单的共用机制；对外隐藏 `emit`；对**调用方**的 DSL 用法零破坏。

**非目标**：不改变菜单的实际运行逻辑（纯发包、`isAsync=true` 的更新模型保持）；不引入真实 Bukkit 容器；不重写包层 DSL（`PacketScope` 等）；不处理分页菜单的语义变化（沿用现有 `PageableChestMenuBuilder`）。

## 4. 总体架构

```
API (platform-bukkit-api) —— 纯声明式
  DSL：ChestMenuScope / PageableChestMenuScope / PlayerMenuScope / SlotScope<C>
  事件：MenuEvent(+Open/Close) · SlotEvent(+SlotClick/InventoryClick/Interact/SlotUpdate)
  句柄：Menu : EventSource<MenuEvent> · ChestMenu · PlayerInventoryMenu · MenuFactory · MenuRegistry
  （不再暴露 Slot / SlotClickListener / SlotUpdateListener）

common-api
  事件读写分离：EventSource<E>（订阅侧） ⊂ EventBus<E>（+ emit）

IMPL (platform-bukkit-impl)
  声明层  SlotSpec(不可变) ← SlotBuilder（实现 api 的 SlotScope<C>）
  运行层  LiveSlot（可变 item + 包缓存 + 渲染） · SlotGrid（Map<Int,LiveSlot>） · MenuUpdateLoop
  共享层  AbstractVirtualMenu（菜单总线 / 观察者 / grid / 更新循环 / 包监听生命周期 / 点击派发）
  具体层  VirtualChestMenu · VirtualPlayerInventoryMenu（仅各自差异）
  装配层  VirtualMenuFactory（实现 MenuFactory）· VirtualMenuRegistry（实现 MenuRegistry）
```

核心三原则：**声明与运行态分家**；**总线放菜单粒度而非 slot 粒度**；**更新是定时规则而非广播事件**。

## 5. 详细设计

### 5.1 事件 API 读写分离（common-api · `event/Event.kt`）

把 `EventBus` 拆成「订阅侧」`EventSource` 与「完整侧」`EventBus`（后者多一个 `emit`）：

```kotlin
interface EventSource<E : Event> {
    fun <T : E> subscribe(listener: EventListener<T>)
    fun <T : E> unsubscribe(listener: EventListener<T>): Boolean
    fun unsubscribeGroup(group: String): Boolean
    fun unsubscribeAll()
}

interface EventBus<E : Event> : EventSource<E> {
    @Throws(EventException::class) fun emit(event: E)
}
```

`EventScope` 的构造参数与顶层 `on` 扩展的接收者从 `EventBus<in E>` 放宽到 `EventSource<in E>`（它们只用到订阅，不用 `emit`）：

```kotlin
class EventScope<E : Event>(val group: String?, val source: EventSource<in E>) : Disposable { ... }
inline fun <reified T : Event> EventSource<in T>.on(group: String? = null, block: EventScope<T>.() -> Unit): Disposable { ... }
```

**影响面（源码兼容）**：现有所有 `EventBus` 使用方（`AbstractArena`、`AbstractArenaPlayer`、`BukkitArena`、`BukkitEventBridge`、`GuardFeature`/`SpectatorFeature`/`WaitingLobbyFeature`、`SimpleEventBus`）都通过 `EventBus` 拿到 `emit`+订阅，因 `EventBus : EventSource` 不变，`.on{}`/`emit` 全部继续编译，无需改动。收益：任何只想暴露「可订阅、不可 emit」的类型（如 `Menu`）现在有了精确的接口。

### 5.2 公开 API 收敛（platform-bukkit-api）

- **`Menu` 暴露只读事件源**，`emit` 对外隐藏：
  ```kotlin
  interface Menu : Destroyable, EventSource<MenuEvent> {
      fun open(player: Player)
  }
  ```
  外部可 `menu.on { on<MenuOpenEvent>{…}; on<SlotClickEvent>{…} }`，但拿不到 `emit`，无法伪造事件。
- **移除 `Slot`**（`menu/slot/Slot.kt` 删除）。当前没有任何对外入口能拿到 `Slot`，它只是 builder→menu 的内部搬运工；运行态表示下沉到 impl。
- **移除 `SlotClickListener` / `SlotUpdateListener`**（`menu/slot/SlotEventListener.kt` 删除）。新设计中点击是 block、更新是 `UpdateRule`，这两个监听器接口除被废弃实现引用外无人使用。
- **`SlotScope` 去掉冗余的第二泛型**：
  ```kotlin
  @SlotDsl
  interface SlotScope<out C : SlotClickEvent> {
      fun onClick(priority: Priority = Priority.DEFAULT, block: C.() -> Unit)
      fun onUpdate(priority: Priority = Priority.DEFAULT, trigger: TaskScheduler.Trigger, block: SlotUpdateEvent.() -> Unit)
  }
  ```
  当前 `SlotScope<C, *>` 的 `*` 星投影会让 `onUpdate` 的 `this` 退化为 `Nothing`（实际不可用）；固定更新事件为 `SlotUpdateEvent` 后手感恢复正常。DSL 作用域签名相应由 `SlotScope<InventoryClickEvent, *>`/`SlotScope<InteractEvent, *>` 收成单泛型。
- **保留不变**：`MenuEvent`/`MenuOpenEvent`/`MenuCloseEvent`；`SlotEvent`/`SlotClickEvent`/`SlotUpdateEvent`（`SlotUpdateEvent.item` 仍为 `var`）；`InventoryClickEvent(menu, player, index, type)`；`InteractEvent(menu, player, index, type)`；`MenuFactory`/`MenuRegistry`（用户已建）；`ChestMenuType` 等。

### 5.3 声明层（impl · `menu/slot/`）

`SlotSpec` 是**不可变声明**，零运行态：

```kotlin
internal class SlotSpec(
    val item: ItemStack,                         // bukkit，初始物品
    val clickHandlers: List<ClickHandler>,
    val updateRules: List<UpdateRule>,
)
internal class ClickHandler(
    val priority: Priority,
    val type: Class<out SlotClickEvent>,         // 用于总线类型过滤
    val block: SlotClickEvent.() -> Unit,        // 由 C.()->Unit 安全转型而来
)
internal class UpdateRule(
    val trigger: TaskScheduler.Trigger,
    val block: SlotUpdateEvent.() -> Unit,
)
```

`SlotBuilder<C : SlotClickEvent>` 实现 api 的 `SlotScope<C>`，纯收集、不持有总线：

```kotlin
internal class SlotBuilder<C : SlotClickEvent>(private val clickType: Class<C>) : SlotScope<C> {
    private val clicks = mutableListOf<ClickHandler>()
    private val updates = mutableListOf<UpdateRule>()

    override fun onClick(priority: Priority, block: C.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        clicks += ClickHandler(priority, clickType, block as SlotClickEvent.() -> Unit)
    }
    override fun onUpdate(priority: Priority, trigger: TaskScheduler.Trigger, block: SlotUpdateEvent.() -> Unit) {
        updates += UpdateRule(trigger, block)    // ← trigger 不再被丢弃
    }
    fun build(item: ItemStack) = SlotSpec(item, clicks.toList(), updates.toList())
}
```

`block as SlotClickEvent.()->Unit` 的非检查转型是安全的：该 handler 注册到总线时带上 `clickType`，总线以 `type.isInstance(event)` 过滤，故 handler 只会收到 `C` 类型事件。

### 5.4 运行层（impl · `menu/`）

`LiveSlot` 吸收旧 `InternalSlot`，是唯一的「槽运行态」（可变当前物品 + 包物品缓存 + 渲染）：

```kotlin
internal class LiveSlot(private val spec: SlotSpec) {
    @Volatile var item: ItemStack = spec.item            // 异步更新可见性
    val updateRules: List<UpdateRule> get() = spec.updateRules
    val clickHandlers: List<ClickHandler> get() = spec.clickHandlers

    private var lastBukkit: ItemStack? = null
    private var cachedPacket: PacketItemStack = PacketItemStack.EMPTY
    fun packetItem(): PacketItemStack { /* 原 InternalSlot 的按内容缓存逻辑（fromBukkit） */ }
}
```

`SlotGrid` 是槽集合 + 共用操作，消除两个菜单对 `Map<Int, …>` 的重复处理：

```kotlin
internal class SlotGrid(specs: Map<Int, SlotSpec>) {
    private val slots: Map<Int, LiveSlot> = specs.mapValues { LiveSlot(it.value) }
    operator fun get(index: Int): LiveSlot? = slots[index]
    fun packetItems(size: Int): List<PacketItemStack?> = List(size) { slots[it]?.packetItem() }
    fun forEachUpdatable(action: (Int, LiveSlot) -> Unit) =
        slots.forEach { (i, s) -> if (s.updateRules.isNotEmpty()) action(i, s) }
}
```

`MenuUpdateLoop`（替代 `SlotUpdateScheduler`）只依赖 grid + scheduler + 一个 `repaint(index)` 回调，与菜单类型解耦；**每个 `UpdateRule` 各按自己的 trigger 排程**，完整保留原语义：

```kotlin
internal class MenuUpdateLoop(
    private val menu: Menu,
    private val grid: SlotGrid,
    private val scheduler: TaskScheduler,
    private val repaint: (index: Int) -> Unit,
) {
    private val tasks = mutableListOf<Int>()
    fun start() = grid.forEachUpdatable { index, slot ->
        slot.updateRules.forEach { rule ->
            tasks += scheduler.scheduleTask {
                trigger = rule.trigger
                isAsync = true
                onTick = {
                    val event = SlotUpdateEvent(menu, index, slot.item.clone()).apply(rule.block)
                    if (event.item != slot.item) { slot.item = event.item; repaint(index) }
                }
            }
        }
    }
    fun stop() { tasks.forEach(scheduler::cancelTask); tasks.clear() }
}
```

### 5.5 共享层（impl · `menu/AbstractVirtualMenu.kt`）

持有所有共用机制，并把菜单总线委托给一个**私有** `SimpleEventBus`（故 `emit` 不外泄）：

```kotlin
internal abstract class AbstractVirtualMenu(
    scheduler: TaskScheduler,
    specs: Map<Int, SlotSpec>,
    private val bus: SimpleEventBus<MenuEvent> = SimpleEventBus(),
) : VirtualMenu, EventSource<MenuEvent> by bus {   // 仅暴露订阅侧

    protected val grid = SlotGrid(specs)
    private val viewersMut = mutableSetOf<Player>()
    override val activeViewers: Set<Player> get() = viewersMut
    private val loop = MenuUpdateLoop(this, grid, scheduler, ::repaint)
    private var packetSub: Disposable? = null
    final override var isDestroyed = false; private set

    init {
        // 把每个槽声明的 click handler 作为「按 index 过滤」的监听挂到菜单总线
        specs.forEach { (index, spec) ->
            spec.clickHandlers.forEach { h ->
                bus.subscribe(EventListener<SlotClickEvent>(h.type, null, { if (index == this.index) h.block(this) }, h.priority))
            }
        }
        loop.start()
        packetSub = registerPacketListener()
    }

    protected fun publish(event: MenuEvent) = bus.emit(event)   // 仅子类可用
    protected fun addViewer(p: Player) { viewersMut += p; publish(MenuOpenEvent(this, p)) }
    protected fun removeViewer(p: Player): Boolean = viewersMut.remove(p).also { if (it) publish(MenuCloseEvent(this, p)) }

    protected abstract fun repaint(index: Int)                  // 类型相关：给观察者发该槽的物品包
    protected abstract fun registerPacketListener(): Disposable // 类型相关：包→点击事件映射
    protected open fun onDestroy(player: Player) {}             // 类型相关：关窗/还原背包

    final override fun destroy() {
        if (isDestroyed) return
        activeViewers.toList().forEach(::onDestroy)
        loop.stop(); packetSub?.dispose(); bus.unsubscribeAll(); viewersMut.clear()
        isDestroyed = true
    }
}
```

点击的**统一派发口**就是 `publish(event)`：总线按 `type.isInstance` + index 过滤，命中该槽的声明 handler，同时外部 `menu.on{ on<SlotClickEvent>{} }` 的订阅者也会收到。

### 5.6 具体菜单（impl · `menu/type/...`）

只剩各自真正不同的部分，全部共用机制走基类：

- **`VirtualChestMenu`**：从计数器分配 `windowId`；`open()` 发 container-open + 同步物品 + 隐藏玩家背包区；`registerPacketListener` 映射 CLICK_WINDOW（→`publish(InventoryClickEvent(this, player, index, clickType))` 后回发同步包）/ CLOSE_WINDOW（→`removeViewer`）/ OPEN_WINDOW（窗口被顶替则移除观察者）；`repaint(index)` 用 windowId 发 SetSlot。
- **`VirtualPlayerInventoryMenu`**：`windowId = 0`；`activate/deactivate`；`registerPacketListener` 映射 CLICK_WINDOW/ANIMATION/USE_ITEM/PLAYER_DIGGING（→`publish(InteractEvent(this, player, index, InteractionType.*))`）以及 WINDOW_ITEMS/SET_SLOT 发送拦截改写为虚拟物品；`repaint` 用 windowId 0 发 SetSlot。

> 包监听器内的 `onPacketReceive/onPacketSend` 仍由各具体菜单实现（包类型与拦截各异），基类只负责注册/注销这条监听器的生命周期。

### 5.7 装配层：工厂与注册表（impl）

- **`VirtualMenuFactory : MenuFactory`**（即原 `VirtualMenuManager` 改造）：`createChestMenu`/`createPlayerInventoryMenu` 用现有 builder 产出 `Map<Int, SlotSpec>` 并构造具体菜单；创建时向注册表登记菜单。
- **`VirtualMenuRegistry : MenuRegistry`**：用菜单的事件源跟踪状态——对每个新建菜单 `menu.on { on<MenuOpenEvent>{…}; on<MenuCloseEvent>{…} }`，维护 `player → menu` 与 `menu → viewers`，实现 `getActiveMenu/hasActiveMenu/getViewers`。这是对「菜单级事件总线」的自然复用。
- `BukkitEasyLib` 暴露 `menuFactory`（必要时一并暴露 `menuRegistry`，与 `BukkitEasyLibApi` 当前对齐；这一处装配可按你正在进行的 `MenuFactory`/`MenuRegistry` 拆分微调）。

## 6. 数据流

- **构建**：DSL → `SlotBuilder<C>` → `SlotSpec` → builder 产出 `Map<Int,SlotSpec>` → 工厂 → 具体菜单 → 基类建 `SlotGrid`、把 click handler 挂上总线、`loop.start()`、注册包监听、登记注册表。
- **打开**：`open/activate` → 发包渲染 → `addViewer`（→ `MenuOpenEvent`，注册表记录）。
- **点击**：客户端包 → 具体菜单映射成 `InventoryClickEvent`/`InteractEvent` → `publish` 到菜单总线 → 命中 index 的槽 handler + 外部订阅者 → 具体菜单回发权威物品包。
- **更新**：`MenuUpdateLoop` 按各 `UpdateRule.trigger` tick → `SlotUpdateEvent(menu, index, item.clone())` → `rule.block` → 若 `item` 变化则写回 `LiveSlot.item` 并 `repaint(index)` 给观察者。
- **销毁**：`destroy` → 各观察者 `onDestroy` → `loop.stop()` + 注销包监听 + `bus.unsubscribeAll()` + 清观察者。

## 7. 线程模型

沿用现状：更新任务 `isAsync = true`。因为菜单**纯发包**、不触碰真实 Bukkit 容器状态，异步发包是安全的（PacketEvents 发送线程安全）。跨线程可见性靠 `LiveSlot.item` 标 `@Volatile`。不引入主线程切换。

## 8. 使用示例（对调用方零破坏）

```kotlin
val factory = EasyLibApi.api.bukkitApi().menuFactory

val menu = factory.createChestMenu(ChestMenuType.GENERIC_9X3) {
    page(Component.text("我的菜单")) {
        slot(0, Material.GRAY_STAINED_GLASS_PANE)
        slot(13, Material.DIAMOND, metadata = { setDisplayName("§b点我") }) {
            onClick { player.sendMessage("你用 $type 点了第 $index 格") }     // this: InventoryClickEvent
        }
        slot(15, Material.CLOCK) {
            onUpdate(trigger = Trigger.Interval(1.seconds)) {                 // this: SlotUpdateEvent
                item = ItemStack(Material.CLOCK).apply { itemMeta = itemMeta?.apply { setDisplayName("§e$nowText") } }
            }
        }
        closeButton(26)
    }
}
menu.open(player)

// 新增：只读订阅（外部拿不到 emit）
menu.on {
    on<MenuOpenEvent>  { player.sendMessage("打开") }
    on<SlotClickEvent> { /* 任意格点击，带 index */ }
}
```

DSL 写法与现状一致；唯一对调用方可见的变化是「多了 `menu.on{}` 订阅能力 + `onUpdate` 手感修复」。

### 8.1 交互式槽位与背包屏蔽（增量，spec 见 `superpowers/specs/2026-07-02-menu-interactive-slots-design.md`）

菜单级 `hidePlayerInventory`（默认 `true`，保持屏蔽玩家背包的现状）；slot 级 `movable`（物品可被拿走）与 `placeable`（玩家可放入自己的物品）。引擎只维护虚拟层，**真实物品的给予/扣除由插件在 `onTake`/`onPlace` 回调中实现**（可取消）：

```kotlin
val trade = factory.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
    page(Component.text("交易")) {
        // 可被拿走的奖励：回调负责真实给予
        slot(11, ItemStack(Material.DIAMOND), movable = true) {
            onTake {                                        // this: SlotTakeEvent(item, targetSlot, ...)
                if (player.inventory.addItem(item).isNotEmpty()) isCancelled = true  // 背包满 → 取消
            }
        }
        // 玩家可放入物品的投入口（初始为空）：回调负责真实扣除
        slot(15, ItemStack(Material.AIR), placeable = true) {
            onPlace {                                       // this: SlotPlaceEvent(item, sourceSlot, ...)
                val src = player.inventory.getItem(sourceSlot)
                if (src?.isSimilar(item) != true || src.amount < item.amount) { isCancelled = true; return@onPlace }
                if (src.amount == item.amount) player.inventory.setItem(sourceSlot, null)
                else src.amount -= item.amount              // 右键放置可能只放入部分数量
            }
        }
        closeButton(26)
    }
}
trade.open(player)

val deposited: ItemStack? = trade.getItem(15)   // 读取玩家放入的物品（AIR/未声明 → null）
trade.setItem(15, null)                          // 清空并 repaint
```

注意：`placeable` 要求 `hidePlayerInventory = false`（否则构建期报错）；shift-快移/数字键/双击/拖拽在 v1 一律安全回退（取消+重刷）。

## 9. 改动清单

**common-api（1 改）**
- `event/Event.kt`：新增 `EventSource<E>` 超接口，`EventBus<E> : EventSource<E>` 保留 `emit`；`EventScope`/顶层 `on` 接收者放宽为 `EventSource`。源码兼容。

**platform-bukkit-api（2 删 / 3 改）**
- 删 `menu/slot/Slot.kt`、`menu/slot/SlotEventListener.kt`。
- 改 `menu/Menu.kt`（`: EventSource<MenuEvent>`）、`menu/slot/dsl/SlotScope.kt`（单泛型 `SlotScope<C>`）、`menu/type/chest/dsl/ChestMenuDsl.kt` 与 `menu/type/player/dsl/PlayerMenuDsl.kt`（`SlotScope<…>` 去掉 `, *`）。
- 不动：`MenuFactory.kt`、`MenuRegistry.kt`、各事件类型、`InventoryClickEvent`/`InteractEvent`。

**platform-bukkit-impl（5 新 / 多改 / 2 删）**
- 新增：`menu/slot/SlotSpec.kt`、`menu/slot/LiveSlot.kt`、`menu/SlotGrid.kt`、`menu/AbstractVirtualMenu.kt`、`menu/MenuUpdateLoop.kt`。
- 修改：`menu/slot/SlotBuilder.kt`（实现 `SlotScope<C>`，产 `SlotSpec`）、三个 builder（`ChestMenuBuilder`/`PageableChestMenuBuilder`/`PlayerMenuBuilder`，工厂签名改 `Map<Int,SlotSpec>`）、`VirtualChestMenu.kt`/`VirtualPlayerInventoryMenu.kt`（继承 `AbstractVirtualMenu`，仅留差异）、`VirtualMenuManager.kt`→`VirtualMenuFactory.kt`（+ `VirtualMenuRegistry`）、`BukkitEasyLib.kt`（装配 `menuFactory`）。`menu/MenuExt.kt` 保留。
- 删除：`menu/internal/InternalSlot.kt`（并入 `LiveSlot`）、`menu/SlotUpdateScheduler.kt`（由 `MenuUpdateLoop` 取代）。

## 10. 测试策略

复用 MockBukkit + JUnit + MockK：
- **声明层（纯单测）**：`SlotBuilder.onClick/onUpdate` 正确收集成 `SlotSpec`（含 trigger 不丢）；`build` 物品正确。
- **更新循环（用假 scheduler）**：注入记录型 `TaskScheduler`，验证每个 `UpdateRule` 各排一条任务、trigger 正确；tick 时 item 变化才 `repaint`，不变则跳过；`stop()` 取消全部。
- **派发（用记录型 handler）**：构造带 spec 的菜单，`publish(InventoryClickEvent(...))` 只命中对应 index 且类型匹配的 handler；外部 `menu.on{}` 也收到；销毁后 `unsubscribeAll` 生效。
- **注册表**：开/关菜单后 `getActiveMenu/getViewers` 正确（由 Open/Close 事件驱动）。
- 包级集成（发包字节）较难单测，作为手动/集成验证，文档标注。

## 11. 兼容性与迁移

- **调用方 DSL 零破坏**：`createChestMenu/createPlayerInventoryMenu` + `slot{ onClick/onUpdate }` + `open` 不变。
- **API 收缩**：移除 `Slot`、`SlotClickListener`、`SlotUpdateListener`——均无对外使用方。
- **`EventSource` 拆分源码兼容**：现有 Arena/Feature 等 `EventBus` 使用方不受影响。

## 12. 风险与取舍

- **更新不走总线**：更新经 `MenuUpdateLoop` 直接调 `rule.block`，不在菜单总线上 `emit`，以保「每个 rule 各按自己 trigger」语义。代价：外部 `menu.on{}` 订阅不到「更新」事件（点击、开关可订阅）。若日后需要，可在 `repaint` 后追加一条总线广播，但不属本次范围。
- **点击经总线带来轻微行为改进**：点击 handler 现按 priority 有序、且单个 handler 异常被总线 try/catch 隔离——相对旧的直接 `forEach` 是增强，不破坏语义。
- **`registerPacketListener` 仍在具体菜单内**：包类型差异大，强行抽象收益低；基类只统一其注册/注销生命周期，刻意不过度抽象。
- **注册表依赖菜单事件**：`VirtualMenuRegistry` 通过订阅 Open/Close 跟踪观察者，与你正在做的 `MenuFactory`/`MenuRegistry` 拆分对齐；若你对注册表有不同装配意图，此处可调整。
