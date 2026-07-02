# MenuAPI 交互式 Slot 与背包屏蔽配置 — 设计文档

日期：2026-07-02
状态：已获用户批准（待实现）
范围模块：`platform-bukkit-api`、`platform-bukkit-impl`

## 1. 背景与现状（已核实的代码库事实）

EasyLib 的菜单子系统（commit `64365c9` 重构后）是**纯数据包虚拟菜单**：

- `VirtualChestMenu` 通过 `containerOpen` 包打开窗口（windowId 自 114514 递增），服务端 Bukkit/NMS 层**不存在**对应容器；所有点击以 `CLICK_WINDOW` 包到达（Netty 线程），当前一律 `isCancelled = true`，随后发布 `InventoryClickEvent`（对每个 involved slot），并重刷：虚拟光标置空 + 涉及 slot 刷为 `grid.packetItem(slot)`（未声明的 slot 得到 `EMPTY`）。
- `hidePlayerInventoryItems()`（`VirtualChestMenu.kt:121-125`）当前**无条件**在 open 时把玩家背包区刷成空气；关闭时经 `player.updateInventory()` 恢复。
- 槽位声明模型：`ChestMenuScope.slot(index/range, item, metadata, block)` → `SlotBuilder` → 不可变 `SlotSpec(item, clickHandlers, updateRules)` → 运行态 `LiveSlot`（`@Volatile var item`）聚合于共享 `SlotGrid`。
- 菜单实例**多玩家共享**（`activeViewers` + 共享 grid，`repaint` 广播）。
- 事件设施：菜单内部 `SimpleEventBus<MenuEvent>`；`ClickHandler(priority, type: Class<out SlotClickEvent>, block)` 在 `AbstractVirtualMenu.init` 注册为按 index 过滤的监听器。可取消事件模式：`Event.Cancellable { var isCancelled: Boolean }`（common-api）。
- 调度设施：`TaskScheduler.scheduleTask { }`，默认 `Trigger.Once` + `isAsync = false`（主线程一次性任务）。
- 包设施：`Player.sendPackets { forPlayer { containerSetSlot / containerItems / updateCursorItem / updateItem } }`（common-packetevents DSL + `BukkitPacketManager`）。

### 现存 bug（本次顺带修复）

1. **off-by-one**：`hidePlayerInventoryItems` 的循环 `for (i in type.size - 1 until type.size + 36)` 从 `type.size - 1`（最后一个菜单 slot）开始，会把它清成空气。正确范围为 `type.size until type.size + 36`（36 个背包槽：27 主背包 + 9 热键栏）。
2. **重刷污染**（隐性）：`handleClickWindow` 对背包区 slot 重刷 `EMPTY`。在"背包永远隐藏"的现状下恰好正确；一旦 hide 可配置为 false，该路径必须改为 `player.updateInventory()`，否则会把玩家真实物品视觉抹除。

## 2. 需求与用户决策记录

三个新功能：

- ① slot 级配置 `movable`：slot 中的 item 可被玩家移动（拿起）。
- ② 菜单级配置 `hidePlayerInventory`：打开菜单时是否用数据包屏蔽玩家背包物品（关闭后恢复）。现状为硬编码永远屏蔽，本功能将其变为可配置。
- ③ slot 级配置 `placeable`：是否允许玩家把自己背包的物品放到该 slot（默认不允许）。

设计澄清阶段的用户决策：

| 决策点 | 用户选择 |
|---|---|
| 真实物品语义 | **回调全权决定**：引擎不移动任何真实物品，只派发可取消的边界事件（take/place），插件在回调中自行实现给予/扣除 |
| 多观看者共享 | **保持共享**：交互 slot 状态全局共享（像共享箱子）；需要 per-player 菜单的插件自行为每个玩家 create 实例 |
| 交互模型 | **标准光标模型**：支持左/右键拿起、放下、半组拆分、同类堆叠、（纯虚拟）交换；shift-快移、数字键、双击收集、拖拽在 v1 一律取消+重刷 |
| API 形态 | **函数参数式**：`slot(index, item, movable = …, placeable = …)`、`createChestMenu(type, hidePlayerInventory = …)` |

## 3. 范围

仅 `ChestMenu`（`VirtualChestMenu`）。`PlayerInventoryMenu` 不参与：其"菜单"即玩家背包窗口本身，三个语义在该场景要么不成立要么完全不同。

v1 明确收敛（安全回退为拒绝+重刷）：

- 光标（背包源）与 slot 中异类物品的交换：不支持。
- 真实背包内部重排（背包源光标放到另一个背包槽位）：不支持；放回原槽位允许（视觉还原）。
- shift-快移 / 数字键 / 双击收集 / 拖拽：不支持。

## 4. API 变更（platform-bukkit-api）

### 4.1 工厂

```kotlin
interface MenuFactory {
    fun createPlayerInventoryMenu(builder: PlayerMenuScope.() -> Unit): PlayerInventoryMenu
    fun createChestMenu(
        type: ChestMenuType,
        hidePlayerInventory: Boolean = true,   // 新增；默认 true 保持现有行为
        builder: PageableChestMenuScope.() -> Unit,
    ): ChestMenu
}
```

`hidePlayerInventory` 作用于该菜单的**所有页**（分页由多个 `VirtualChestMenu` 实例组成，参数逐层透传）。

### 4.2 slot 声明

```kotlin
@ChestMenuDsl
interface ChestMenuScope {
    fun slot(
        index: Int,
        item: ItemStack,
        movable: Boolean = false,      // 新增
        placeable: Boolean = false,    // 新增
        metadata: ItemMeta.() -> Unit = {},
        block: (SlotScope<InventoryClickEvent>.() -> Unit)? = null,
    )
    // range 重载同构；两个 Material 便捷扩展函数同步加参
}
```

初始为空的可放置 slot 用 `ItemStack(Material.AIR)` 声明。`PlayerMenuScope.slot` 不变（其 `SlotSpec` 的两个 flag 恒为 false）。

### 4.3 新事件

复用现有 `ClickHandler` / index 过滤注册机制，故继承 `SlotClickEvent`：

```kotlin
class SlotTakeEvent(
    menu: Menu, index: Int, player: Player,   // index = 来源菜单 slot
    val item: ItemStack,                       // 被取走物品（Bukkit 副本）
    val targetSlot: Int,                       // 玩家点击的目标真实背包槽位（Bukkit 语义 0-35），供回调精确放置
    override var isCancelled: Boolean = false,
) : SlotClickEvent(menu, index, player), Event.Cancellable

class SlotPlaceEvent(
    menu: Menu, index: Int, player: Player,   // index = 目标菜单 slot
    val item: ItemStack,                       // 待放入物品（Bukkit 副本）
    val sourceSlot: Int,                       // 来源真实背包槽位（Bukkit 语义 0-35）
    override var isCancelled: Boolean = false,
) : SlotClickEvent(menu, index, player), Event.Cancellable
```

回调职责契约：`SlotTakeEvent` 未取消 ⇒ 插件应给予真实物品（如 `player.inventory.addItem`）；`SlotPlaceEvent` 未取消 ⇒ 插件应从 `sourceSlot` 扣除真实物品。引擎在事件后调用 `player.updateInventory()` 渲染回调造成的真实变化。

### 4.4 订阅便捷方法

```kotlin
@SlotDsl
interface SlotScope<out C : SlotClickEvent> {
    fun onClick(priority: Priority = Priority.DEFAULT, block: C.() -> Unit)
    fun onUpdate(trigger: TaskScheduler.Trigger, priority: Priority = Priority.DEFAULT, block: SlotUpdateEvent.() -> Unit)
    fun onTake(priority: Priority = Priority.DEFAULT, block: SlotTakeEvent.() -> Unit)    // 新增
    fun onPlace(priority: Priority = Priority.DEFAULT, block: SlotPlaceEvent.() -> Unit)  // 新增
}
```

v1 仅箱子菜单会触发这两类事件（player 菜单声明了也不触发，KDoc 注明）。也可经 `menu.on { on<SlotPlaceEvent> { … } }` 订阅。

### 4.5 Menu 内容访问

```kotlin
interface Menu : Destroyable, EventSource<MenuEvent> {
    fun open(player: Player)
    fun getItem(index: Int): ItemStack?                // 新增：未声明 slot 或当前为 AIR 均返回 null（统一表示"空"）
    fun setItem(index: Int, item: ItemStack?)          // 新增：写入并 repaint；未声明 slot 抛 IllegalArgumentException；null 等价 AIR
}
```

这是插件读取玩家放入物品、以及事件外主动改写 slot 内容的必要入口（实现落在 `AbstractVirtualMenu`，两种菜单同享）。

## 5. 运行时设计（platform-bukkit-impl）

### 5.1 数据流

`SlotSpec` 增加 `val movable: Boolean, val placeable: Boolean`；`SlotBuilder.build` / `ChestMenuBuilder.buildSlot` / `PageableChestMenuBuilder` / `VirtualMenuManager` 逐层透传；`VirtualChestMenu` 构造函数增加 `hidePlayerInventory: Boolean`。

### 5.2 点击状态机（新内部类 `ChestClickEngine`）

per-player 虚拟光标：`CursorStack(item: ItemStack, origin: Origin)`，`Origin = MenuSlot(index) | PlayerInventory(windowSlot)`。仅接管 `WindowClickType.PICKUP`（左/右键）；其余类型走"拒绝+重刷"。决策矩阵（S = 点击的窗口槽位；菜单区 = `S < type.size`）：

| # | 光标 | 点击 | 前置条件 | 行为 |
|---|---|---|---|---|
| 1 | 空 | 菜单区 | `movable` 且 slot 有物品 | 虚拟拿起：左=全部，右=⌈n/2⌉；纯虚拟，无边界事件 |
| 2 | 空 | 背包区 | `hide=false` 且菜单含 ≥1 placeable | 虚拟拿起真实物品（不动真实背包，记录来源槽位）；否则拒绝 |
| 3 | 菜单源 | 菜单区 | 目标 `placeable` 且空/同类可堆叠；**或目标 == 来源槽位且空/同类（放回，无条件允许）**；异类交换需目标 `movable && placeable` | 菜单内虚拟移动/堆叠/交换/放回；纯虚拟，无边界事件 |
| 4 | 背包源 | 菜单区 | 目标 `placeable` 且空/同类可堆叠（异类交换拒绝） | 主线程派发 `SlotPlaceEvent` → 未取消：commit（grid 更新 + repaint + 清/减光标 + `updateInventory()`）；取消：回滚重刷 |
| 5 | 菜单源 | 背包区 | — | 主线程派发 `SlotTakeEvent`（index = origin 槽位）→ 未取消：清光标 + （`hide=false` → `updateInventory()`；**`hide=true` → 背包区重刷空气，维持屏蔽**，真实物品在关闭后可见）；取消：光标维持 + 重刷点击槽位 |
| 6 | 背包源 | 背包区 | 仅 S == 来源槽位 | 放回 = 视觉还原（清光标+重刷）；其它槽位拒绝 |
| 7 | — | 其余一切 | — | 拒绝：重刷涉及菜单区 slot + 清光标 + （`hide=false` → `updateInventory()`；`hide=true` → 背包区刷空气） |

堆叠遵守 `ItemStack.maxStackSize`，"同类"按 Bukkit `ItemStack.isSimilar` 语义判定。右键放置 = 放 1 个。所有 commit / 回滚 / 拒绝路径都显式重发虚拟光标包（`updateCursorItem`）与涉及 slot 的权威状态，不依赖客户端预测。

### 5.3 线程模型

`CLICK_WINDOW` 到达 Netty 线程：仅做 windowId 匹配 + `e.isCancelled = true` + 调度。状态机全部经 `scheduler.scheduleTask { onTick = { … } }`（`Trigger.Once`、同步）落到**主线程**执行（含纯虚拟分支），统一规避共享 grid 的复合写竞态，并让回调天然处于主线程（可安全操作 Bukkit API）。处理期间同一玩家的新点击直接拒绝+重刷（per-player pending 标志，主线程置/清）。包发送在主线程进行（`playerManager.sendPacket` 线程安全）。

兼容性：现有 `InventoryClickEvent` 对所有点击**照常发布**（发布时机随状态机移至主线程；此前在 Netty 线程发布，对行为良好的处理器无影响，KDoc 注明）。事件顺序：同一次点击先发布信息性的 `InventoryClickEvent`（不可取消，维持现状），后发布可取消的边界事件（`SlotTakeEvent`/`SlotPlaceEvent`），由后者决定 commit。实现时需核实 `SimpleEventBus` 的类型匹配语义（精确类 vs `isAssignableFrom`），确保 `onClick`（`InventoryClickEvent`）监听不会误收 take/place 事件（两者是 `SlotClickEvent` 的兄弟子类，正常匹配下互不干扰）。

### 5.4 光标生命周期

- 关闭窗口 / 断线 / `destroy()`：菜单源光标物品归还 origin slot；若 origin 已被占用（共享竞态或插件 `setItem`），丢弃该虚拟物品并记 debug 日志——真实物品从未产生，无实际损失。背包源光标无需处理（真实物品从未离开背包），`updateInventory()` 即还原。
- `removeViewer` 时同步清理该玩家光标与 pending 标志。

### 5.5 hide 相关修复

- `hidePlayerInventoryItems` 范围改为 `type.size until type.size + 36`，且仅在 `hidePlayerInventory = true` 时调用。
- 拒绝路径的重刷按 §5.2 第 7 行区分 hide 与否。

## 6. 构建期校验与错误处理

- `hidePlayerInventory = true` 且任何页声明了 `placeable` slot：静态矛盾（背包不可见则玩家永远无法拿起物品来放置），构建时 `require` 失败并给出明确消息。
- `movable`/`placeable` 对 `ItemStack(AIR)` 初始 slot 合法（placeable 的典型形态）。
- 事件回调抛异常：按"已取消"处理（回滚 + 日志），保证虚拟状态不被插件 bug 破坏。
- commit 前校验 `player.isOnline` 与 `player in activeViewers`，不满足则丢弃本次操作。

## 7. 测试策略

决策核心抽为与包收发解耦的纯逻辑（输入：点击参数 + 光标状态 + SlotSpec + hide 配置 → 输出：`Decision`，即 Allow(变更集)/Deny/FireTake/FirePlace），JUnit + MockK 覆盖 §5.2 全部分支矩阵——这是唯一能脱离真实客户端可靠测试的层。另覆盖：

- `SlotBuilder`/`SlotSpec`/builder 链路对两个 flag 的透传。
- `hidePlayerInventory + placeable` 的 `require` 校验。
- hide 槽位范围（off-by-one 修复）的纯函数测试。
- MockBukkit 冒烟：构建菜单、`getItem`/`setItem`、手动 `publish` 验证 `onTake`/`onPlace` 派发与取消语义。

## 8. 兼容性说明

- `slot(...)` 新参数插在 `item` 与 `metadata` 之间：具名调用与 trailing lambda 不受影响；按位置传 `metadata` 的调用会编译期报错（显式失败，无静默行为变化）。
- `createChestMenu` 加默认参数会改变二进制签名（Kotlin default 参数），JitPack 下游需重编译。当前处于 dev 分支菜单子系统刚重构的窗口期，可接受。
- 默认值（`hidePlayerInventory = true`、两 flag 为 false）下运行时行为与现状一致（除 off-by-one 修复：最后一个菜单 slot 不再被误清）。
- `Menu` 接口新增两个方法，对库外部自定义 `Menu` 实现是源不兼容的（本库设计上不支持外部实现 `Menu`，实例只应经 `MenuFactory` 创建）。

## 9. 最小使用案例

```kotlin
val factory = EasyLibApi.api.bukkitApi().menuFactory

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

## 10. 不做的事（Out of Scope）

- `PlayerInventoryMenu` 的交互能力。
- shift-快移 / 数字键 / 双击收集 / 拖拽的语义支持（保持取消+重刷）。
- 引擎自动的真实物品转移（永久排除在本设计外：属回调职责）。
- per-viewer 隔离的 slot 状态。
- 按物品类型过滤的放置策略对象（布尔 flag 未来可无痛演化为策略，YAGNI）。
