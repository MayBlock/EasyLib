# ChestMenu 真实容器化 + PlayerInventoryMenu 独立为 PlayerOverlay API — 设计文档

日期：2026-07-05
状态：已获用户批准（待写实现计划）
范围模块：`platform-bukkit-api`、`platform-bukkit-impl`（顺带 `gradle/libs.versions.toml`）

## 0. 背景与动机

当前菜单子系统是纯数据包虚拟菜单：`VirtualChestMenu` 发 `containerOpen` 包、拦截 `CLICK_WINDOW`、用 `ChestClickLogic`+`ChestClickEngine` 手动重实现一整台容器点击状态机。它最初为「固定不可变展示菜单」而设计——服务端无真实容器，物品结构上无法被抽出。

上一轮为其加了 `movable`/`placeable`/`onTake`/`onPlace`，让菜单开始与真实玩家背包交换物品。这与虚拟菜单的初衷相悖：一旦物品要跨越「菜单⇄真实背包」边界，就必须补齐整台容器状态机（我们只做了 PICKUP，其余走"拒绝+重刷"），且每个生命周期边缘都要手动兜（断线丢物 bug 即一例）。

结论（经技术探讨确认）：**virtual 适合「看」，真实 Bukkit Inventory 适合「拿」。** 故本设计将 ChestMenu 改为真实容器实现；而 PlayerInventoryMenu（覆盖玩家自身背包窗口，无法用真实容器）保持数据包模式，但从 MenuAPI 独立出来、重命名为 **PlayerOverlay**。

## 1. 已锁定的设计决策（来自澄清阶段）

| 决策点 | 选择 |
|---|---|
| 公开 DSL 兼容 | **保持源码兼容**：`createChestMenu{ slot{ movable/placeable/onClick/onTake/onPlace/onUpdate } }` 签名不变，只换底层实现 |
| 编译目标 | **保持 spigot-api**；`Component` 标题经 `LegacyComponentSerializer.legacySection()` 降级为 `§` 码字符串 |
| 实例模型 | **共享单实例**：一个真实 `Inventory` 支持多玩家同时观看（Bukkit 原生 `getViewers`）|
| 迁移策略 | **大爆炸替换**：删除 `VirtualChestMenu` 及其 packet 引擎，ChestMenu 只剩真实容器实现 |
| onTake/onPlace 契约 | **降为门+观察**：物品由 Bukkit 原生移动；回调取消=阻止该次取/放，不取消=放行；不再手动给/扣 |
| hidePlayerInventory | **保留**，构建期可配（`createChestMenu(type, hidePlayerInventory=…)`），实现仍走数据包 |
| PlayerOverlay 能力 | **仅展示 + 交互**，去掉 movable/placeable（该模型不引入这两个概念），其余原样保留 |
| PlayerOverlay 命名 | 见 §2 |

## 2. 命名方案（PlayerOverlay）

新 API 定名 **PlayerOverlay**（玩家背包覆盖层）：本质是"用数据包在玩家自己的背包窗口上叠加虚拟显示并捕获交互"，overlay 精准表达"盖在真实背包之上、非真实容器"，与 `Menu`/`ChestMenu` 词义区分清晰。

| 现在（MenuAPI 内） | 新（独立 PlayerOverlay API） |
|---|---|
| `PlayerInventoryMenu` | `PlayerOverlay` |
| `MenuFactory.createPlayerInventoryMenu { }` | `PlayerOverlayFactory.create { }` |
| `PlayerMenuScope` | `PlayerOverlayScope` |
| `activate(p)` / `deactivate(p)` / `open(p)` | `show(p)` / `hide(p)` |
| `InteractEvent` + `InteractionType`（Inventory/Interact 二合一）| 拆为 `OverlayClickEvent`（背包窗口内点击，带 `ClickType`）+ `OverlayInteractEvent`（手持挥动/使用，带 `LEFT_CLICK`/`RIGHT_CLICK`）|
| `MenuOpenEvent`/`MenuCloseEvent`（复用）| `OverlayShowEvent`/`OverlayHideEvent`（独立事件层）|

## 3. 整体结构：一次拆分为两个独立子系统

当前 ChestMenu 与 PlayerInventoryMenu 共用 packet 机制（`AbstractVirtualMenu`、`LiveSlot`、`SlotGrid`、`MenuUpdateLoop`、packet 版 `MenuExt`、`SlotGrid.packetItem` 缓存）。改造后二者实现路径彻底分叉：

- **ChestMenu** → 真实 Bukkit 容器（`Inventory` + 自定义 `InventoryHolder` + Bukkit 事件监听），**不再用任何 packet 机制**。
- **PlayerOverlay** → **继续用** packet 机制，把上述共用类**迁入 overlay 包并归其独占**（不是删除，是从"共享"降级为"overlay 私有"）。

被真正删除的只有 ChestMenu 专属的 packet 交互引擎（§4.9）。

---

## 4. Part A：ChestMenu → 真实容器

### 4.1 核心思路（对齐 InvUI / Canvas 主流范式）

菜单成为一个真实的顶部 `Inventory`；一个共享的 Bukkit `Listener` 按 `event.inventory.holder` 找回对应菜单；**默认取消所有点击（不可变）**，再按每个 slot 的 `movable`/`placeable` 选择性放行 Bukkit 原生动作。物品移动由 Bukkit 原生执行，我们只做"选择性取消 + 观察"。

### 4.2 组件

- **`MenuInventoryHolder : InventoryHolder`**（impl，internal）：持有对 `RealChestMenu` 的引用；`getInventory()` 返回该菜单的真实 `Inventory`。事件路由的锚点。
- **`RealChestMenu`**（impl，internal，替代 `VirtualChestMenu`）：实现 `ChestMenu`；持有 `Bukkit.createInventory(holder, type.size, legacyTitle)` 的真实 `Inventory`（多观看者共享此单一实例）；持菜单级事件总线（`SimpleEventBus<MenuEvent>`，用于外部 `menu.on{}` 订阅与内部 slot handler 派发，沿用现有 index 过滤注册）；持一个主线程更新循环。
- **`MenuInteractionListener : Listener`**（impl，internal，注册在 plugin 上，全局单一实例）：监听 `InventoryClickEvent`/`InventoryDragEvent`/`InventoryOpenEvent`/`InventoryCloseEvent`，用 `event.inventory.holder as? MenuInventoryHolder` 找回菜单并转交处理。复用代码库既有模式（`ItemExtensionApiImpl` 已按 `InventoryHolder` 路由 `InventoryClickEvent`；`registerEvents(this, plugin)` + `HandlerList.unregisterAll`）。
- **`ChestSlotGate`**（impl，internal，**纯逻辑、可单测**）：见 §4.3。
- **`ShiftIntoMenuPlanner`**（impl，internal，**纯逻辑、可单测**）：给定被 shift 的来源物品 + 各 placeable 槽的当前内容（按索引有序），计算分发计划 `List<Placement(slotIndex, amount)>`（先填同类未满堆叠、再填空槽，遵守 `maxStackSize`，直至来源耗尽或无空间）。不含副作用；监听器据此逐槽触发 `SlotPlaceEvent` 并应用到真实 `Inventory`。
- **`MenuManager`**（改造自 `VirtualMenuManager`）：仍实现 `MenuFactory`+`MenuRegistry`，构造 `RealChestMenu`；持有并注册/注销 `MenuInteractionListener`；因需注册 Bukkit 监听，构造函数新增 `plugin: Plugin` 参数（`BukkitEasyLib` 处已有 plugin 在作用域）。

### 4.3 per-slot 放行门（`ChestSlotGate`，纯逻辑）

默认 deny（取消事件）。放行规则按 `InventoryAction` 与被作用 slot 的 flag 判定。抽为纯函数便于全矩阵单测（输入：`clickedInventory` 是顶部/底部、`InventoryAction`、被点 slot 的 `movable`/`placeable`、`hidePlayerInventory` → 输出：`Decision`＝allow/cancel + 需触发的 slot 事件）：

| InventoryAction 类别 | 规则 |
|---|---|
| `PICKUP_*`（从 slot 取到光标）| 顶部且该 slot `movable` → 放行；否则取消 |
| `PLACE_*`（光标放入 slot）| 顶部且该 slot `placeable` → 放行；否则取消 |
| `SWAP_WITH_CURSOR` | 顶部且 slot 同时 `movable && placeable` → 放行；否则取消 |
| `DROP_*_SLOT`（从 slot 丢弃）| 顶部且 slot `movable` → 放行；否则取消 |
| `MOVE_TO_OTHER_INVENTORY`（shift）| 点顶部：该 slot `movable` → 放行（Bukkit 原生移到玩家背包，触发 onTake）。点底部（shift 入菜单）：**取消 Bukkit 原生分发，改为手动受控分发**——按 slot 索引顺序**仅向 `placeable` 槽**分发（先填同类未满堆叠、再填空 placeable 槽，遵守 `maxStackSize`），每个目标槽触发可取消的 `SlotPlaceEvent`（取消则跳过该槽、继续下一个）；分发结束后把成功放入的量从来源背包槽扣除。若无任何可放置空间（placeable 槽全满/被异类占用/无 placeable 槽）→ 维持取消、不移动。分发计划由纯函数 `ShiftIntoMenuPlanner`（§4.2）计算，便于单测 |
| `HOTBAR_SWAP`/`HOTBAR_MOVE_AND_READD`（数字键）| 点顶部：slot `movable && placeable` → 放行；否则取消 |
| `COLLECT_TO_CURSOR`（双击收集）| 跨多格、**保守取消**（v1） |
| `CLONE_STACK`（创造中键）| 取消（防创造复制） |
| `NOTHING`/`UNKNOWN` | 取消 |
| 底部（玩家背包）非跨容器的普通点击 | `hidePlayerInventory=true`：取消（背包已被数据包屏蔽，不应操作被隐藏的真实物品）；`hidePlayerInventory=false`：放行（玩家整理自己背包）|

`InventoryDragEvent`：拖拽目标是玩家明确划过的 slot（Bukkit 只放入这些槽，不跨格分发），故仅当触及的每个**顶部** slot 都 `placeable` 时放行原生拖拽、并逐个 placeable 目标槽触发 `SlotPlaceEvent`（原生拖拽只能整体取消，故任一目标被取消 → 取消整次拖拽）；触及任一不可放置的顶部 slot → 取消（触及底部按 hide 规则）。

### 4.4 事件与回调

- **`onClick`**（信息性，保留）：对已声明 slot 的任意点击，在主线程触发 `InventoryClickEvent(menu, player, index, ClickType)`。用于按钮类 slot（如 closeButton）。**先于**门决策与 take/place 触发。
- **`onTake`/`onPlace`（门+观察）**：保留 `SlotTakeEvent`/`SlotPlaceEvent` 类型与 DSL 写法（源码兼容）。契约变为：`movable` slot 被取走 → 触发 `SlotTakeEvent`（取消=阻止本次取出）；`placeable` slot 被放入 → 触发 `SlotPlaceEvent`（取消=阻止本次放入）。回调**不再手动给/扣物品**——物品移动由引擎负责。物品移动**通常由 Bukkit 原生完成**（直接光标 `PLACE_*`、拖拽、原生 shift-取出）；**唯一例外是 shift-入菜单**，为约束"仅向 placeable 分发"，取消原生事件、由引擎手动应用分发（§4.3）——这对回调契约透明，回调在两种情形下都只把关、不手动给/扣。多目标情形（shift 分发、拖拽到多个 placeable 槽）**逐目标槽触发 `SlotPlaceEvent`**，某槽取消则仅跳过该槽。事件字段 `item` 保留（被取/放的物品，多目标时为该槽对应的量）；`targetSlot`/`sourceSlot` 在原生取出下不总能精确得知，保留为 best-effort，取不到时置 -1，KDoc 注明。
- 触发顺序：`onClick`（信息）→ `onTake`/`onPlace`（门，其 `isCancelled` 决定是否 `event.setCancelled(true)` 取消 Bukkit 事件）。
- **`MenuOpenEvent`/`MenuCloseEvent`**：由 `InventoryOpenEvent`/`InventoryCloseEvent` 原生驱动（主线程）。`InventoryCloseEvent` 在**关窗和断线时都会触发**——上一轮的断线清理缺口彻底消失。

### 4.5 onUpdate 改主线程

真实 `Inventory.setItem` 必须主线程，故 ChestMenu 更新循环从 `isAsync=true` 改为主线程调度（`Trigger.Interval` + `isAsync=false`）。相对现状的行为变化：异步刷新 → 主线程刷新。必要且可接受。

### 4.6 getItem / setItem 简化

直接读写真实顶部容器：`getItem(index) = inventory.getItem(index)?.takeUnless{ it.isEmptyStack() }`（对交互 slot 反映玩家放入/取走后的真实内容）；`setItem(index, item) = inventory.setItem(index, item)`。比虚拟 grid 更直观；不再需要 `LiveSlot`/packetItem 缓存。

### 4.7 hidePlayerInventory（保留、构建期可配）

`createChestMenu(type, hidePlayerInventory=…) { }` 签名不变。实现仍走数据包：菜单打开时对「当前打开窗口的玩家背包区 slot」发置空包，关闭时 `player.updateInventory()` 恢复。与真实顶部容器正交。§4.3 底部点击规则已随此 flag 分流。

### 4.8 标题与分页

`Component` 标题经 `LegacyComponentSerializer.legacySection().serialize(component)` 得 `§` 码字符串传 `createInventory`。**需给版本目录新增依赖 `adventure-text-serializer-legacy`（与 `adventureApi` 同版本 5.1.1）**，在 impl 以 `implementation` 引入。富文本标题退化为传统颜色码——「保持 spigot-api」的已知代价。分页仍是「每页一个独立 `Inventory`」，翻页 = open 下一页的 `RealChestMenu`，无需运行时改标题（Spigot 不支持改已开窗口标题）。

### 4.9 删除 / 保留清单（大爆炸）

**删除**：`VirtualChestMenu`、`ChestClickLogic`、`ChestClickEngine`、`ChestClickRenderer`、`ChestClickLogicTest`、`ChestClickEngineTest`、chest 的 packet 监听逻辑。
**保留并复用**：`SlotSpec`（含 movable/placeable 声明）、`SlotBuilder`、`ChestMenuBuilder`、`PageableChestMenuBuilder`、DSL（`ChestMenuScope`/`PageableChestMenuScope`/`SlotScope`）、`SlotTakeEvent`/`SlotPlaceEvent`、`SlotClickEvent`/`InventoryClickEvent`、`Menu`/`ChestMenu`/`MenuEvent`/`MenuFactory`/`MenuRegistry`、`isEmptyStack()`。
**移交给 PlayerOverlay（§5）**：`AbstractVirtualMenu`、`LiveSlot`、`SlotGrid`、`MenuUpdateLoop`、packet 版 `MenuExt`。ChestMenu 侧新增精简基类（事件总线 + 观察者 + 主线程更新循环 + 真实 Inventory + Bukkit 监听生命周期），替代原 `AbstractVirtualMenu` 的职责。

---

## 5. Part B：PlayerInventoryMenu → 独立的 PlayerOverlay API

### 5.1 能力：仅展示 + 交互，去掉 movable/placeable

新 API 从不与真实背包做物品转移，故其 slot 模型**不引入** movable/placeable。**保留**：46 格窗口覆盖（合成格+盔甲+主背包+热键栏+副手，`OVERLAY_SIZE=46`）、逐格虚拟显示、`onUpdate` 定时刷新（**仍异步**——overlay 纯发包，异步安全）、点击事件（背包窗口内点击 → `OverlayClickEvent`）、交互事件（手持挥动/使用 → `OverlayInteractEvent`）、丢弃拦截（Q/Ctrl-Q 被吃、物品不真掉）、`show`/`hide` 生命周期。

### 5.2 从 MenuAPI 剥离

- 新包：`com.github.mayblock.easylib.api.bukkit.overlay`（API）、`com.github.mayblock.easylib.impl.bukkit.overlay`（impl）。
- 新类型：`PlayerOverlay`（接口，不再继承 `Menu`）、`PlayerOverlayFactory`（`create(builder): PlayerOverlay`）、`PlayerOverlayScope`（DSL）、`OverlayEvent`/`OverlayShowEvent`/`OverlayHideEvent`/`OverlayClickEvent`/`OverlayInteractEvent`。
- 迁入并独占的 packet 机制（保持 packet 语义、含异步刷新）：`AbstractVirtualMenu`→`AbstractPlayerOverlay`、`LiveSlot`、`SlotGrid`、`MenuUpdateLoop`→`OverlayUpdateLoop`、packet 版 `MenuExt`。overlay 自有的 slot 声明模型（`OverlaySlotSpec`：item + 点击/交互 handler + update rule，**无** movable/placeable）与 `OverlaySlotBuilder`。
- 入口：`BukkitEasyLibApi` 新增 `overlayFactory: PlayerOverlayFactory`，与 `menuFactory` 并列。

### 5.3 MenuAPI 侧收敛

`MenuFactory` 去掉 `createPlayerInventoryMenu`；删除 `PlayerInventoryMenu`、`PlayerMenuScope`、`PlayerMenuBuilder`、`VirtualPlayerInventoryMenu`、`InteractEvent`/`InteractionType`（迁移/重命名到 overlay 包）。`Menu` 阵营只剩真实容器 ChestMenu 一种，概念更纯。

### 5.4 调用方迁移

`SpectatorService`（`impl/.../game/arena/service/SpectatorService.kt`）当前用 `api.menuFactory.createPlayerInventoryMenu(...)`，迁移为 `api.overlayFactory.create(...)`（`api` 处新增 `overlayFactory` 访问）。这是本次唯一的内部调用方破坏点，必须同步更新。

---

## 6. 测试策略

- **ChestSlotGate（纯逻辑单测）**：JUnit + MockBukkit（需 `InventoryAction`/`ItemStack`），覆盖 §4.3 全部动作类别 × movable/placeable × hide 的放行/取消矩阵。这是真实容器路线的核心正确性层，也是最可测的层。
- **ShiftIntoMenuPlanner（纯逻辑单测）**：给定来源物品 + placeable 槽当前内容，验证分发计划正确（先填同类未满堆叠、再填空槽、遵守 `maxStackSize`、部分放入、无空间返回空计划、跳过被异类占用的非空非同类槽）。
- **RealChestMenu 集成测试**：MockBukkit 仿真 `InventoryClickEvent`/`InventoryDragEvent`/`InventoryOpenEvent`/`InventoryCloseEvent`，验证事件路由、onClick/onTake/onPlace 触发与取消、getItem/setItem、MenuOpen/Close。MockBukkit 对真实 Inventory 事件的支持优于 packet 层——这是改真实容器的又一收益。
- **PlayerOverlay**：沿用现有 packet 层可测部分（slot 声明透传、更新循环、事件派发的单测）+ 手动验证包层渲染。
- **回归**：保留并适配现有仍适用的菜单测试；删除随 `ChestClickLogic`/`ChestClickEngine` 一并退役的测试。

## 7. 迁移与兼容

- **公开 ChestMenu DSL 源码兼容**：`createChestMenu{ slot{ movable/placeable/onClick/onTake/onPlace/onUpdate } }` 不改。
- **运行时语义变化（ChestMenu）**：onTake/onPlace 契约（手动转移 → 门+观察）、onUpdate 线程（异步 → 主线程）。因功能刚建、无线上依赖，代价低；文档明确注明。
- **PlayerOverlay 破坏性迁移**：`createPlayerInventoryMenu` → `overlayFactory.create`，类型重命名+移包。已同意独立。内部唯一调用方 `SpectatorService` 同步迁移。
- **新增依赖**：`adventure-text-serializer-legacy`（版本目录 + impl）。

## 8. 风险与取舍

- **shift-click/拖拽/数字键/双击的 per-slot 放行门**是真实容器路线的固有复杂度，需枚举 `InventoryAction` 全集并逐类处理（§4.3）。有 Canvas/InvUI 成熟范式可循，且远比 packet 路线"重新实现整台状态机"轻。其中 shift-入菜单采用手动受控分发（仅向 placeable 槽、纯函数 `ShiftIntoMenuPlanner`），拖拽按逐格明确目标放行，直接光标 `PLACE_*` 原生放入。**唯一在 v1 保守取消的是 `COLLECT_TO_CURSOR`（双击收集）**——它跨全库聚合匹配物品到光标、目标不确定且方向为"取"，逐格约束成本高、收益低，v1 取消，后续可精细化。
- **共享单实例 + 主线程更新**：多观看者共享一个真实 Inventory，`onUpdate` 主线程 `setItem` 广播给所有观看者，语义与现状一致。
- **标题降级**：富文本 → legacy 颜色码，「保持 spigot-api」的取舍，已知可接受。

## 9. 不做的事（Out of Scope）

- 不把 PlayerOverlay 改为真实容器（玩家自身背包窗口无法作为可 open 的容器）。
- 不为 ChestMenu 保留 virtual 实现（大爆炸替换）。
- v1 不处理 `COLLECT_TO_CURSOR`（双击收集）的逐格约束（一律取消）。shift-入菜单已在 v1 支持（仅向 placeable 槽的受控分发，§4.3）。
- 不改动 Arena/Feature 等其它子系统（除 `SpectatorService` 的必要调用方迁移）。

## 10. 建议实现顺序

一份 spec，两个部分，拆两个 plan 顺序推进：
1. **Plan A**：ChestMenu 真实容器化（含删除 packet 引擎、`ChestSlotGate`、`RealChestMenu`、监听器、标题依赖、hide 保留、getItem/setItem、onUpdate 主线程）。
2. **Plan B**：PlayerOverlay 独立（迁移 packet 机制、重命名、去 movable/placeable、`overlayFactory` 接入、`SpectatorService` 迁移、`MenuFactory` 收敛）。

两部分代码路径独立；A 先行可让 packet 机制的"移交"边界更清晰（A 完成后 chest 不再引用被移交类，B 再把它们迁入 overlay 包）。
