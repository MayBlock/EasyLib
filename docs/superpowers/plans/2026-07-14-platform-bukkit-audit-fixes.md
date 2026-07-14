# platform-bukkit-* 审计修复与清理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 2026-07-14 审计发现的确认 BUG（menu/overlay/arena/基础设施 + common 层顺带发现），删除已核实的冗余代码，并执行经用户批准的破坏性 API 改进。

**Architecture:** 按子系统分任务：先删冗余（缩小改动面）→ common 层地基 → menu → overlay → arena/bridge → 基础设施 → API 破坏性变更收口 → 回归测试补齐。每个任务独立可测、独立提交。

**Tech Stack:** Kotlin/JVM 25、Gradle wrapper、JUnit Platform + MockK、MockBukkit（Bukkit 集成测试）、PacketEvents、FastBoard、nbt-api。

## Global Constraints

- 分支：从 `dev` 创建 `audit/platform-bukkit-fixes`，全部提交在该分支。
- **执行者每次修改前必须先 Read 目标文件**，核对计划中引用的代码与实际一致；不一致时以实际代码为准调整（计划引用行号为 2026-07-14 快照）。
- 不得改动未提交的 WIP 文件：`platform-bukkit-impl/.../extension/OfflinePlayerInventory.kt`、`common-impl/.../util/map/ObservableMap.kt`（用户决定保留自行开发）。也不要把它们 git add。
- 每个任务完成后运行 `./gradlew :platform-bukkit-impl:test :common-impl:test`（Windows: `gradlew.bat`），必须全绿再提交。
- API 模块 public 成员即使仓库内无调用也可能供下游使用，不删除；impl 内已核实无引用路径的才删除。
- 破坏性 API 变更已获用户批准，但每项变更需在提交信息中以 `BREAKING:` 标注。
- commit message 末尾加 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 提交遵循 conventional commits（feat/fix/refactor/test/chore），与仓库现有风格一致。

---

### Task 0: 建立分支

- [ ] `git checkout -b audit/platform-bukkit-fixes dev`
- [ ] `./gradlew :platform-bukkit-impl:test :common-impl:test` 确认基线全绿（记录基线结果）。

---

### Task 1: 删除已核实冗余代码

**Files:**
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenu.kt`（删 `specOf`:117、`fireClickForTest`:144-146）
- Modify: `platform-bukkit-impl/src/test/kotlin/com/github/mayblock/easylib/impl/bukkit/menu/type/chest/RealChestMenuTest.kt`（改用真实路径或删除对应用例）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/LiveSlot.kt`（删 `handlers` 属性:20）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/overlay/AbstractPlayerOverlay.kt`（`bus` 从构造参数降为私有字段）
- Delete: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/util/BlockExt.kt`（整文件零引用）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/util/PlayerExt.kt`（删 `Collection<Player>.sendMessage`、`Collection<Player>.sendTitle`；保留被 SpectatorFeature 调用的 `Player.sendTitle`）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/util/PacketEventsExt.kt`（删 `ItemStack.toBukkit()`，保留 `fromBukkit`）
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/util/ItemStackExt.kt`（删 `ItemStack.onInteract`:30-32）

**Interfaces:** 无对外接口变化（全部为 impl 内部无引用符号）。

- [ ] **Step 1:** 逐文件删除上述符号前，对每个符号再跑一次 `Grep`（模式为符号名）确认除定义/自身测试外无引用。
- [ ] **Step 2:** `AbstractPlayerOverlay` 第 22-26 行改为：

```kotlin
internal abstract class AbstractPlayerOverlay(
    scheduler: TaskScheduler,
    specs: Map<Int, OverlaySlotSpec>,
) : PlayerOverlay, EventSource<OverlayEvent> by SimpleEventBusHolder.hold() // 不可这样写——见下
```

实际写法（委托需要可引用实例，Kotlin 惯用法是私有构造属性改为顶层初始化）：

```kotlin
internal abstract class AbstractPlayerOverlay private constructor(
    scheduler: TaskScheduler,
    specs: Map<Int, OverlaySlotSpec>,
    private val bus: SimpleEventBus<OverlayEvent>,
) : PlayerOverlay, EventSource<OverlayEvent> by bus {
    constructor(scheduler: TaskScheduler, specs: Map<Int, OverlaySlotSpec>) : this(scheduler, specs, SimpleEventBus())
```

即：主构造私有化并保留 bus 参数（委托语法需要），公开二参构造，外部不再能注入 bus。两个调用方（`PacketPlayerOverlay.kt:31`、测试 `TestOverlay`）本就传 2 个实参，无需改动。
- [ ] **Step 3:** `RealChestMenuTest` 中调用 `fireClickForTest`/`specOf` 的用例：改为构造 Bukkit `InventoryClickEvent` 走 `handleClick`（参考 `RealChestMenuClickTest.kt` 已有做法），无法等价改写的用例删除（其覆盖已由 RealChestMenuClickTest 提供）。
- [ ] **Step 4:** `./gradlew :platform-bukkit-impl:test` → 全绿。
- [ ] **Step 5:** `git add -A（排除 WIP 文件）&& git commit -m "refactor: remove verified dead code across menu/overlay/util"`

---

### Task 2: common 层地基修复

**Files:**
- Modify: `common-impl/src/main/kotlin/com/github/mayblock/easylib/impl/game/arena/AbstractArena.kt`
- Modify: `common-impl/src/main/kotlin/com/github/mayblock/easylib/impl/feature/SimpleFeatureRegistry.kt`（路径以实际为准）
- Modify: `common-impl/.../PreGameCountdownFeature.kt:68`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/util/TaskExt.kt`
- Modify: `platform-bukkit-impl/src/main/kotlin/com/github/mayblock/easylib/impl/bukkit/util/ItemStackExt.kt`
- Test: `common-impl/src/test/kotlin/.../ArenaLifecycleTest.kt`（新建）、`platform-bukkit-impl/src/test/kotlin/.../util/ItemStackExtTest.kt`（新建）

**Interfaces:**
- Produces: `ItemStack.meta {}` 修复后语义 = 修改后**写回** itemMeta 并返回同一 ItemStack（Task 3 的翻页按钮显示名依赖它）。

- [ ] **Step 1（失败测试先行）:** 新建 `ItemStackExtTest`：

```kotlin
@Test
fun `meta block modifications are persisted to the item`() {
    val server = MockBukkit.mock()
    try {
        val item = item(Material.ARROW).meta { setDisplayName("Next") }
        assertEquals("Next", item.itemMeta?.displayName)
    } finally { MockBukkit.unmock() }
}
```

运行确认 FAIL（当前 meta 是 no-op）。
- [ ] **Step 2:** 修复 `ItemStackExt.kt:23-28`：

```kotlin
@JvmName("metaWithType")
inline fun <reified T : ItemMeta> ItemStack.meta(block: T.() -> Unit): ItemStack {
    require(!this.type.isAir) { "Cannot set metadata on air item" }
    val meta = (this.itemMeta as? T)
        ?: throw IllegalArgumentException("this item's ItemMeta is not ${T::class.simpleName}")
    block(meta)
    this.itemMeta = meta
    return this
}
```

- [ ] **Step 3（失败测试先行）:** 新建 `ArenaLifecycleTest`（common-impl，纯 JVM 无需 MockBukkit）：断言「`onEnableArena` 抛异常后 `isArenaEnabled` 仍为 false」。当前实现应 FAIL。
- [ ] **Step 4:** 修复 `AbstractArena` 的 enable setter：catch 到 `onEnableArena()` 异常时**不落 `field = value`**（把 `field = value` 放在生命周期回调成功之后，或 catch 中直接 return），不要再用「setter 内递归复位」的写法。同文件 `addPlayer` 的报错文案把 arena 名换成玩家标识（如 `"Player ${player.name} already exists in arena $name"`，以实际字段为准）。
- [ ] **Step 5:** `SimpleFeatureRegistry`：`uninstall(key)` 前检查是否存在已安装 feature 的 `dependencies` 含该 key，有则抛 `IllegalStateException`（消息列出依赖者）；`uninstallAll()` 改为按依赖图逆拓扑序卸载（简单实现：反复扫描卸载「无人依赖」者直至清空；出现环时按剩余顺序强卸并 warn）。为两者补单测。
- [ ] **Step 6:** `PreGameCountdownFeature.kt:68` 的 `Interval(1.milliseconds)` → `Interval(50.milliseconds)`；`TaskExt.toTicks()` 结果 `coerceAtLeast(1)` 并加 KDoc 说明「不足 1 tick 的时长按 1 tick 执行」。检查 `WaitingLobbyFeature.kt:48` 的 ticks 换算调用点是否随之仍正确。
- [ ] **Step 7:** `./gradlew :common-impl:test :platform-bukkit-impl:test` 全绿 → commit `fix(common,util): arena enable rollback, feature uninstall ordering, meta write-back, tick coercion`

---

### Task 3: Menu 子系统 BUG 修复

**Files:**
- Modify: `platform-bukkit-impl/.../menu/MenuManager.kt`
- Modify: `platform-bukkit-impl/.../menu/type/chest/RealChestMenu.kt`
- Modify: `platform-bukkit-impl/.../menu/MenuInteractionListener.kt`
- Modify: `platform-bukkit-impl/.../menu/type/chest/ChestSlotGate.kt`
- Modify: `platform-bukkit-impl/.../menu/type/chest/builder/ChestMenuBuilder.kt`、`PageableChestMenuBuilder.kt`
- Test: 新建 `PageableChestMenuBuilderTest.kt`；扩展 `ChestSlotGateTest`、`MenuManagerChestTest`、`RealChestMenuTest`

**Interfaces:**
- Produces: `RealChestMenu` 新增构造参数 `private val onDestroyed: (RealChestMenu) -> Unit = {}`（`destroy()` 末尾调用，internal 类不破坏 API）；`MenuManager` 私有方法 `register` 保持 `<M : Menu> register(menu: M): M` 签名。

- [ ] **Step 1（BUG: 分页脱管）失败测试:** `PageableChestMenuBuilderTest`（MockBukkit）：创建 2 页菜单，断言 ① `MenuManager.getViewers(page2)` 在玩家翻到第 2 页后包含该玩家（即第 2 页 Open 事件被 manager 订阅）；② `MenuManager.close()` 后第 2 页 `isDestroyed == true`。当前实现 FAIL。
- [ ] **Step 2:** 修复 `MenuManager.createChestMenu`（当前 36-45 行）——把注册塞进工厂 lambda，每页都登记：

```kotlin
override fun createChestMenu(
    type: ChestMenuType,
    hidePlayerInventory: Boolean,
    builder: PageableChestMenuScope.() -> Unit
): ChestMenu =
    PageableChestMenuBuilder(type) { title, slots ->
        register(RealChestMenu(taskScheduler, title, type, slots, hidePlayerInventory, onDestroyed = ::forget))
    }.apply(builder)
        .build() // build() 返回的第 1 页已在工厂内注册，删除原来的 .let(::register)
```

- [ ] **Step 3（BUG: menus 只增不减）:** `RealChestMenu` 加构造参数 `onDestroyed: (RealChestMenu) -> Unit = {}`，`destroy()` 末尾 `onDestroyed(this)`。`MenuManager` 新增：

```kotlin
private fun forget(menu: Menu) {
    menus.remove(menu)
    activeMenus.entries.removeIf { it.value === menu }
}
```

- [ ] **Step 4（BUG: destroy CME）:** `RealChestMenu.destroy()` 第 150 行改 `bukkitInventory.viewers.toList().forEach { it.closeInventory() }`，并加注释说明 CraftBukkit 返回 live 列表。
- [ ] **Step 5（BUG: 无视他插件取消）:** `MenuInteractionListener` 的 click/drag 两个 `@EventHandler` 增加 `ignoreCancelled = true`（close 监听保持不变，InventoryCloseEvent 不可取消）。
- [ ] **Step 6（BUG: 断线无兜底）:** `MenuInteractionListener` 新增：

```kotlin
@EventHandler
fun onQuit(e: PlayerQuitEvent) {
    (e.player.openInventory.topInventory.holder as? RealChestMenu)?.handleClose(e.player)
}
```

（若服务端在 quit 前已触发 InventoryCloseEvent，`handleClose` 双调无害：`hideViewers -= player` 幂等，publishClose 重复派发需防——在 `handleClose` 增加幂等保护：只有 `activeMenus` 语义由 manager 端 `if (activeMenus[player] === menu)` 已保护；为防重复 MenuCloseEvent，`RealChestMenu` 用 `hideViewers` 之外再维护 `openViewers: MutableSet<Player>`，`publishOpen` 加入、`handleClose` 仅当移除成功才 `publishClose`）。
- [ ] **Step 7（BUG: 多实例重复处理）:** `MenuInteractionListener` 构造改为接收 owner `MenuManager`，路由时校验菜单属于本 manager：`RealChestMenu` 增加 `internal var owner: MenuManager?`（register 时赋值），listener 中 `if (menu.owner !== manager) return`。
- [ ] **Step 8（BUG: hide=true shift-take 进入不可见背包）:** `ChestSlotGate.decide`：当 `hidePlayerInventory == true` 且顶部 `MOVE_TO_OTHER_INVENTORY` 时返回 `Deny`（原 `FireTake` 放行分支仅在 hide=false 保留）。同步修改 `ChestSlotGateTest` 中相应期望，并补 hide=true 分支矩阵用例。
- [ ] **Step 9（防御: shift 入菜单）:** `handleShiftIntoMenu`（222-242 行）写入前复核容量：

```kotlin
val existing = bukkitInventory.getItem(p.slot)
if (existing == null || existing.isEmptyStack()) {
    bukkitInventory.setItem(p.slot, placing)
    placedTotal += p.amount
} else {
    val room = existing.maxStackSize - existing.amount
    val add = minOf(room, p.amount)
    if (add <= 0) continue
    existing.amount += add
    placedTotal += add
}
```

（`placedTotal` 累加从循环尾移入分支内，剩余量计算逻辑不变。）
- [ ] **Step 10（builder 篡改入参 + 差一文案 + 导航冲突）:** `ChestMenuBuilder.kt:57` 与 `PageableChestMenuBuilder.kt:47-49,57-59` 对入参 `item` 先 `clone()` 再改 meta；`ChestMenuBuilder.kt:31,43` require 消息改 `[0, $size)`；`PageableChestMenuBuilder.build()` 给导航槽 `page.slot(index, item)` 前检查该 index 是否已被用户声明，冲突则 `throw IllegalArgumentException("slot $index is reserved for page navigation")`。
- [ ] **Step 11:** `RealChestMenu.getItem` 返回 `?.clone()`，`Menu.kt:18` KDoc 注明返回拷贝。
- [ ] **Step 12:** `./gradlew :platform-bukkit-impl:test` 全绿 → commit `fix(menu): register all pages, lifecycle cleanup, cancellation respect, hide-mode shift gate`

---

### Task 4: Overlay 子系统 BUG 修复

**Files:**
- Modify: `platform-bukkit-impl/.../overlay/AbstractPlayerOverlay.kt`
- Modify: `platform-bukkit-impl/.../overlay/PacketPlayerOverlay.kt`
- Modify: `platform-bukkit-impl/.../overlay/OverlayManager.kt`
- Modify: `platform-bukkit-impl/.../overlay/OverlayQuitListener.kt`
- Modify: `platform-bukkit-impl/.../game/arena/service/SpectatorService.kt`
- Test: 扩展 `AbstractPlayerOverlayTest`、`OverlayManagerTest`

**Interfaces:**
- Produces: `AbstractPlayerOverlay` 新增 `internal var onDestroyed: (() -> Unit)? = null`（destroy 末尾调用）；`internal fun hideIfViewing(player: Player)`（供容器打开兜底调用）。

- [ ] **Step 1（BUG: 数据竞争）:** `AbstractPlayerOverlay.kt:29` 改 `private val viewers: MutableSet<Player> = ConcurrentHashMap.newKeySet()`；`repaint` 遍历（PacketPlayerOverlay:55）已 `toList()` 快照，在循环体内补 `if (player !in activeViewers) return@forEach` 防 hide 后补发鬼影。
- [ ] **Step 2（BUG: 反取消他插件）:** `PacketPlayerOverlay.onPacketReceive`（70-82 行）改为：

```kotlin
val shouldCancel = when (e.packetType) { /* 原 when 内容不变 */ }
if (shouldCancel) e.isCancelled = true
```

- [ ] **Step 3（BUG: 创造模式刷物品）:** `onPacketReceive` 的 when 增加分支：

```kotlin
PacketType.Play.Client.CREATIVE_INVENTORY_ACTION -> {
    val packet = WrapperPlayClientCreativeInventoryAction(e)
    // 覆盖层激活期间创造背包操作一律取消并重发权威遮罩，防止虚拟物品落入真实背包
    if (packet.slot in 0 until PlayerOverlay.OVERLAY_SIZE) {
        player.sendPackets { forPlayer { updateItem(0, packet.slot, grid.packetItem(packet.slot)) } }
        true
    } else false
}
```

（wrapper 类名/字段以 PacketEvents 实际 API 为准，执行者先查 `com.github.retrooper.packetevents.wrapper.play.client` 包。）
- [ ] **Step 4（BUG: 右键方块/攻击实体用真实物品 + F 键副手）:** when 增加：

```kotlin
PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT ->
    handleInteract(player, OverlayInteractEvent.Action.RIGHT_CLICK)
PacketType.Play.Client.INTERACT_ENTITY ->
    handleInteract(player, OverlayInteractEvent.Action.LEFT_CLICK)
```

并修改 `handleDropItem`（137-143 行）：`action == SWAP_ITEM_WITH_OFFHAND` 时若手持槽或槽 45 任一在 grid 声明中，取消并 resync 两槽（`updateItem(0, heldItemSlot, ...)` + `updateItem(0, 45, ...)`），返回 true。
- [ ] **Step 5（BUG: THROW 畸形包抛异常）:** `handleClickWindow:106-110` 的 `else -> throw UnsupportedOperationException()` 改为 `else -> return true`（吞掉畸形包并注释原因）。
- [ ] **Step 6（BUG: 点击事件槽位语义）:** `handleClickWindow` 派发事件改用 `packet.slot`（真实点击槽）：`publish(OverlayClickEvent(this, packet.slot, player, clickType))`；resync 仍遍历 `involvedSlots + packet.slot`。
- [ ] **Step 7（BUG: 打开其他容器绕过遮罩）:** `AbstractPlayerOverlay` 增加：

```kotlin
internal fun hideIfViewing(player: Player) {
    if (player in activeViewers) removeViewer(player).also { if (it) onHide(player) }
}
```

`OverlayQuitListener` 增加监听：

```kotlin
@EventHandler
fun onInventoryOpen(e: InventoryOpenEvent) {
    val player = e.player as? Player ?: return
    overlays().forEach { it.hideIfViewing(player) }
}
```

（`overlays()` 为 OverlayQuitListener 现有获取 overlay 集合的途径，按实际结构接入；玩家自己背包视图不触发 InventoryOpenEvent，不会误伤。）在 `PlayerOverlay` 接口 KDoc 注明「打开任意容器界面会自动隐藏覆盖层」。
- [ ] **Step 8（BUG: 泄漏链）:** `AbstractPlayerOverlay.destroy()` 末尾调用 `onDestroyed?.invoke()`；`OverlayManager.create` 里对每个 overlay 设置 `onDestroyed = { overlays.remove(overlay) }`（字段名以实际为准）；`SpectatorService.onUnregister()`：

```kotlin
override fun onUnregister() {
    spectators.toList().forEach(::removeSpectator)
    playerOverlay.destroy()
}
```

- [ ] **Step 9（事件线程契约）:** `handleClickWindow`/`handleInteract` 中 `publish(...)` 改为经 `taskScheduler` 调度到主线程执行（resync 发包保留在 netty 线程）；`AbstractPlayerOverlay` 需要持有 scheduler 引用（当前构造参数 `scheduler` 未存字段，升级为 `protected val`）。在 `OverlaySlotScope` 的 onClick/onInteract KDoc 声明「主线程回调」。
- [ ] **Step 10:** destroy 后 `show/hide` 行为写进 API 契约：`PlayerOverlay.kt` KDoc 增加 `@throws IllegalStateException`。
- [ ] **Step 11:** 测试：`AbstractPlayerOverlayTest` 补「destroy 后 onDestroyed 回调触发」「hideIfViewing 移除 viewer 并派发 HideEvent」；`OverlayManagerTest` 补「overlay destroy 后 manager 不再持有」。`./gradlew :platform-bukkit-impl:test` 全绿 → commit `fix(overlay): packet interception hardening, thread safety, lifecycle leaks`

---

### Task 5: Arena / Bridge BUG 修复

**Files:**
- Modify: `platform-bukkit-impl/.../game/arena/AbstractBukkitArena.kt`
- Modify: `platform-bukkit-impl/.../game/arena/bridge/BukkitEventBridge.kt`
- Modify: `platform-bukkit-impl/.../game/arena/bridge/BridgeEvent.kt`
- Modify: `platform-bukkit-impl/.../game/arena/AbstractBukkitArenaPlayer.kt`
- Modify: `platform-bukkit-impl/.../game/arena/feature/ScoreboardFeature.kt`、`GuardFeature.kt`、`WaitingLobbyFeature.kt`、`PlayerJoinLeaveFeature.kt`
- Modify: `platform-bukkit-api/.../game/arena/BukkitArena.kt`、`BukkitArenaPlayer.kt`（破坏性，见步骤）
- Test: 新建 `platform-bukkit-impl/src/test/kotlin/.../game/arena/bridge/BukkitEventBridgeTest.kt`、`.../game/arena/ArenaBridgeLifecycleTest.kt`（MockBukkit）

**Interfaces:**
- Produces（BREAKING）: `BukkitArena.createArenaEntity(entity: Entity): E?`（返回可空，null = 本 arena 不关心该实体）；`BukkitArenaPlayer.location: Location?`（可空化）。

- [ ] **Step 1（失败测试先行）:** `BukkitEventBridgeTest`（MockBukkit）覆盖三件事：① 其他监听器已取消的事件不被 bridge 反取消；② arena 监听器修改 `PlayerMoveEvent.to` 后 Bukkit 事件的 `to` 被真正更新；③ 未 enable 的 arena 收到 `EntitySpawnEvent` 不抛异常。三者当前均 FAIL。
- [ ] **Step 2（BUG: onMove 写回 no-op）:** `BukkitEventBridge.kt:157-158` 改：

```kotlin
if (e.to != ae.to) {
    ae.to?.let(e::setTo)
}
```

- [ ] **Step 3（BUG: 反取消他插件）:** 全部桥接 handler 统一两条规则：① `BridgeEvent` 构造后立即 `isCancelled = e.isCancelled`（继承原生初值，让 arena 监听器可感知并可改）；② 写回改为 `e.isCancelled = bridgeEvent.isCancelled`（此时语义正确：初值继承后，未改动=保持原状）。`onTarget`/`onToggleSneak` 的优先级统一为 `EventPriority.LOWEST` 与其余一致。`onTarget` 的 `e.target = target.bukkitPlayer` 改为仅当 arena 监听器实际改了 target 且非 null 时写回（对照初值判断）。
- [ ] **Step 4（BUG: 实体桥接灾难）:** 重构 `onEntitySpawn`（216-223 行）并接活三个死方法：

```kotlin
@EventHandler(priority = EventPriority.LOWEST)
fun onEntitySpawn(e: EntitySpawnEvent) {
    if (e.entity is Player) return
    if (!arena.isArenaEnabled) return
    val entity = arena.createArenaEntity(e.entity) ?: return // BREAKING: 返回可空
    when (e) {
        is SpawnerSpawnEvent -> onSpawnerSpawn(entity, e)
        is CreatureSpawnEvent -> onCreatureSpawn(entity, e)
        else -> onGenericEntitySpawn(entity, e)
    }
    if (!e.isCancelled) arena.spawnEntity(entity)
}
```

三个 onXxxSpawn 方法保持私有、按 Step 3 的取消语义修正。`BridgeEvent.kt:135-153` 的 EntitySpawn 子类删除「重新 override var isCancelled 造成双 backing field」的写法，正确向 super 传递。`platform-bukkit-api/BukkitArena.kt` 的 `createArenaEntity` 返回类型改 `E?`，KDoc 说明 null 语义（BREAKING）。
- [ ] **Step 5（BUG: bridge 生命周期）:** `AbstractBukkitArena` 重构：

```kotlin
abstract class AbstractBukkitArena<Player : BukkitArenaPlayer, Entity : BukkitArenaEntity>(
    name: String,
    protected val plugin: Plugin
) : AbstractEventfulArena<Player, Entity>(name), BukkitArena<Player, Entity> {

    private var bridge: BukkitEventBridge<*, Player, Entity>? = null

    override fun onEnableArena() {
        super.onEnableArena()
        bridge = BukkitEventBridge.create(this, plugin)
    }

    override fun onPostDisableArena() {
        bridge?.destroy()
        bridge = null
        super.onPostDisableArena()
    }
}
```

（hook 方法名/是否有 super 实现以 `AbstractEventfulArena`/`AbstractArena` 实际为准；`BukkitEventBridge.destroy()` 同时幂等化：`if (isDestroyed) return`。）用 `ArenaBridgeLifecycleTest` 断言 enable→disable→enable→disable 全程无异常且第二轮事件仍被桥接。
- [ ] **Step 6（BUG: containsAll=addAll + 计分板泄漏）:** `ScoreboardFeature.kt:68` 改 `providers.containsAll(elements)`；`onUninstall` 改：

```kotlin
override fun onUninstall(context: A) {
    taskId?.let(context::cancelTask)
    fastboardCache.values.forEach { it.delete() }
    fastboardCache.clear()
}
```

`refresh` 开头清理已离场玩家：

```kotlin
fastboardCache.keys.retainAll { it in arena.players }  // retainAll 前对被移除者先 delete()
```

实际写法：

```kotlin
val gone = fastboardCache.keys.filter { it !in arena.players }
gone.forEach { fastboardCache.remove(it)?.delete() }
```

任务改同步：`isAsync = true` → `isAsync = false`（消除对 `arena.players`/cache 的跨线程访问；FastBoard 更新是发包，主线程开销可接受）。`FastBoard(player.bukkitPlayer)` 改为先取局部变量判空再构造。
- [ ] **Step 7（BUG: 观战者不还原）:** `SpectatorService.Spectator` 增加进场快照与完整还原：

```kotlin
inner class Spectator internal constructor(val arenaPlayer: BukkitArenaPlayer) {
    private var previousGameMode: GameMode? = null
    private var previousAllowFlight = false
    private var previousFlying = false

    fun apply() {
        val player = arenaPlayer.bukkitPlayer ?: return
        previousGameMode = player.gameMode
        previousAllowFlight = player.allowFlight
        previousFlying = player.isFlying
        // ...原逻辑不变...
    }

    fun restore() {
        stopWatching()
        val player = arenaPlayer.bukkitPlayer ?: return
        previousGameMode?.let { player.gameMode = it }   // 真实 gamemode 变更会重发权威包，纠正假 ADVENTURE
        player.allowFlight = previousAllowFlight
        player.isFlying = previousFlying
        playerOverlay.hide(player)
    }
}
```

已知限制（写入 KDoc）：玩家离线时 restore 无法执行，SPECTATOR 模式会持久化到重登——记录到 Task 8 的报告中。
- [ ] **Step 8（Guard/WaitingLobby/JoinLeave 修正）:**
  - `GuardFeature`：全部 `isCancelled = <条件>` 改为 `if (<条件>) isCancelled = true`；爆炸处理接活 `explode` 标志：`onEntityExplode`/`onBlockExplode` 先判 `scope.explode == false` 才防护；两者统一用「事件位置在范围内即取消」口径（保持简单，不做 blockList 过滤，KDoc 注明）。
  - `WaitingLobbyFeature`：倒计时完成/中止时重置 `player.level = 0`、`player.exp = 0f`（或恢复进场快照，以现有结构最小改动为准）；`ArenaJoinedEvent` handler 加 `if (!isActive()) return@on` 守卫。
  - `PlayerJoinLeaveFeature.SingleWorld`：补 `PlayerJoinEvent`（登录即在目标世界 → onJoin/onRejoin）与 `PlayerQuitEvent`（在目标世界退出 → onQuit）监听，复用现有回调分发逻辑。
- [ ] **Step 9（ArenaPlayer 可空性）:** `AbstractBukkitArenaPlayer.kt:20-21`：`isOnline` 改 `bukkitPlayer?.isOnline == true`（消除双取 getter 竞态）；`location` 改实现为 `bukkitPlayer?.location ?: Bukkit.getOfflinePlayer(uuid).location`，API `BukkitArenaPlayer.location` 类型改 `Location?`（BREAKING），下游调用点全部适配。
- [ ] **Step 10:** `./gradlew :platform-bukkit-impl:test` 全绿 → commit `fix(arena): bridge cancellation semantics, entity spawn opt-in, lifecycle symmetry, feature cleanup` （BREAKING 项在正文列出）

---

### Task 6: 基础设施 BUG 修复

**Files:**
- Modify: `platform-bukkit-impl/.../command/BukkitCommandRegistry.kt`、`command/ProxyCommand.kt`
- Modify: `platform-bukkit-impl/.../prompt/PromptApiImpl.kt`
- Modify: `platform-bukkit-impl/.../BukkitEasyLib.kt`
- Modify: `platform-bukkit-impl/.../scheduler/BukkitTaskScheduler.kt`
- Modify: `platform-bukkit-impl/.../BukkitDispatcherImpl.kt`
- Modify: `platform-bukkit-impl/.../packet/BukkitPacketManager.kt`
- Test: 新建 `.../command/BukkitCommandRegistryTest.kt`（MockBukkit）

**Interfaces:**
- Produces: `BukkitCommandRegistry` 内部新增 `private val registered = mutableMapOf<String, ProxyCommand>()` 用于精确注销。

- [ ] **Step 1（失败测试先行）:** `BukkitCommandRegistryTest`：注册命令 → `unregister` → 断言 `commandMap.getCommand(name) == null` 且命令不可再执行。当前 FAIL（knownCommands 未清）。
- [ ] **Step 2:** `BukkitCommandRegistry` 重写注销：

```kotlin
private val registered = mutableMapOf<String, ProxyCommand>()

private val knownCommands: MutableMap<String, org.bukkit.command.Command> by lazy {
    @Suppress("UNCHECKED_CAST")
    SimpleCommandMap::class.java.getDeclaredField("knownCommands")
        .apply { isAccessible = true }
        .get(commandMap) as MutableMap<String, org.bukkit.command.Command>
}

override fun unregister(commandName: String): Boolean {
    val cmd = registered.remove(commandName.lowercase()) ?: return false
    cmd.unregister(commandMap)
    knownCommands.entries.removeIf { it.value === cmd }  // 同时移除 name、别名、namespace:name
    return true
}

override fun unregisterAll() {
    registered.keys.toList().forEach { unregister(it) }
}
```

`register` 成功后 `registered[command.name.lowercase()] = command`。`commandMap` 获取健壮化：先尝试 `server.javaClass.getMethod("getCommandMap")`（Paper 公开 API），`NoSuchMethodException` 时按类层级向上找 `commandMap` 字段（循环 `superclass` 而非只查 declaredField），均失败抛带指引的 `IllegalStateException`。
- [ ] **Step 3:** `ProxyCommand.kt:33` 别名查表前 `commandLabel.substringAfter(':')`。
- [ ] **Step 4（Prompt 重构）:** `PromptApiImpl` 按以下语义重写（保持 `PromptApi` 接口不变，本任务不改 API）：
  - `promptList` 改 `ConcurrentHashMap<UUID, Pair<Vector3i, (String?) -> Unit>>`，key 为玩家 UUID（消除同坐标撞 key + 线程安全）。
  - 同一玩家二次 openPrompt 时，先以 `null` 结算并移除旧回调。
  - 收包 handler：`val pending = promptList[e.user.uuid或player.uniqueId] ?: return`（**非本 API 的告示牌编辑直接放行**，不再把真实告示牌变空气）；校验 `packet.blockPosition == pending.first` 才处理；处理前 `promptList.remove(uuid)`（消除双 resume）。
  - 方块恢复：不再无脑发 AIR，改为主线程调度 `player.sendBlockChange(location, location.block.blockData)` 还原真实方块。
  - 回调经主线程调度执行（用 `api` 的 TaskScheduler/dispatcher，具体以 BukkitEasyLib 现有能力为准），KDoc 声明线程契约。
  - suspend 版：`suspendCancellableCoroutine` 加 `cont.invokeOnCancellation { promptList.remove(uuid) }`。
  - `BukkitEasyLib` 侧（或 OverlayQuitListener 同级）监听 `PlayerQuitEvent`：`promptList.remove(uuid)?.second?.invoke(null)`。
- [ ] **Step 5（BukkitEasyLib 构造与关闭）:** `EasyLibApi.api = this`（init 块）移动到所有属性初始化完成之后（init 块移到类体最末尾，加注释说明发布时序）；`close()` 补：命令 `unregisterAll()`（Step 2 修复后已安全）、`ItemExtensionApiImpl` 的 Bukkit listener `HandlerList.unregisterAll(...)`、Prompt 的 packet listener dispose（PromptApiImpl 提供 `internal fun shutdown()`）。
- [ ] **Step 6（调度器/派发器加固）:** `BukkitTaskScheduler`：一次性任务先在 map 放占位再调度，回调里自删（消除 put 前完成的竞态）；`BukkitDispatcherImpl`：增加 `override fun isDispatchNeeded(context: CoroutineContext) = !Bukkit.isPrimaryThread()` 替代「dispatch 内就地 run」；`dispatch` 前判 `plugin.isEnabled`，禁用后直接在当前线程执行 runnable 并 warn（避免 IllegalPluginAccessException 静默炸协程）。`BukkitPacketManager`：`PacketEvents.getAPI()` 改惰性获取 + `checkNotNull(...) { "PacketEvents 未初始化，请确认加载顺序" }`。
- [ ] **Step 7:** `./gradlew :platform-bukkit-impl:test` 全绿 → commit `fix(infra): command unregistration, prompt lifecycle, init ordering, scheduler races`

---

### Task 7: API 破坏性变更收口（BREAKING 批次）

**Files:**
- Rename: `platform-bukkit-api/.../menu/slot/InventoryClickEvent.kt` → `MenuClickEvent.kt`（类同名改）
- Modify: `platform-bukkit-api/.../BukkitEasyLibApi.kt`
- Modify: `platform-bukkit-api/.../game/arena/BukkitArena.kt`
- Modify: `platform-bukkit-api/.../extension/ItemExtensionApi.kt`、impl 对应
- Modify: `platform-bukkit-impl/.../BukkitEasyLib.kt`、`menu/MenuManager.kt`

**Interfaces:**
- Produces（BREAKING 清单，逐条列入提交信息）:
  1. `InventoryClickEvent` → `MenuClickEvent`（避开 Bukkit 同名冲突；全仓库引用与测试同步改）。
  2. `BukkitEasyLibApi` 新增 `val menuRegistry: MenuRegistry`（MenuManager 已实现，BukkitEasyLib 暴露同一实例）。
  3. `BukkitEasyLibApi` 命名统一：`promptApi` → `prompts`、`itemExtensionApi` → `items`、`menuFactory` → `menus`、`overlayFactory` → `overlays`（`dispatcher` 不变）；全仓库调用点同步。
  4. `BukkitArena` 改继承 `EventSource<ArenaEvent>` 而非 `EventBus<ArenaEvent>`（外部不可再 emit 伪造事件）；`BukkitEventBridge` 的交叉泛型约束 `where A : EventBus<ArenaEvent>, A : BukkitArena<P, E>` 由此获得真实意义，保留 `create` 工厂；`AbstractEventfulArena` 满足 EventBus 侧，impl 不受影响。执行者需核实 common-api `Arena`/`AbstractEventfulArena` 的继承结构后落地。
  5. `ItemExtensionApi.onInteract/onClick` 返回 `Disposable`（impl 从 handlers map 移除对应条目；顺带修复 handlers 只增不减的泄漏）。
  6. `EasyLibApi.bukkitApi()` 强转失败时抛带指引消息的 `IllegalStateException`。
  7. `MenuManager` 改 `internal`，`BukkitEasyLib` 中对应属性显式标注 API 类型。
  8. `SlotTakeEvent.targetSlot` 从 API 移除（恒为 -1 的假数据；`sourceSlot` 保留——shift 路径有真实值）。
- [ ] **Step 1:** 按清单逐项执行，每项独立编译验证（`./gradlew :platform-bukkit-api:build :platform-bukkit-impl:build`）。
- [ ] **Step 2:** 全仓库 Grep 旧名（`InventoryClickEvent`（api 包名下）、`menuFactory`、`overlayFactory`、`promptApi`、`itemExtensionApi`、`targetSlot`）确认无残留引用。
- [ ] **Step 3:** `./gradlew check` 全绿 → commit `refactor(api)!: rename menu click event, unify BukkitEasyLibApi naming, seal arena event bus (BREAKING)`

---

### Task 8: 回归测试补齐 + 收尾报告

**Files:**
- Test: `platform-bukkit-impl/src/test/kotlin/...`（bridge 取消矩阵、arena 生命周期、pageable、ChestSlotGate hide 矩阵、command registry——前序任务已建的确保齐全；补 `handleClose` 幂等、拖拽 hide 分支、onUpdate 未修改不写回 三个盲区用例）
- Create: `docs/superpowers/plans/2026-07-14-audit-remaining-issues.md`（未修复问题清单）

- [ ] **Step 1:** 核对前序任务的测试覆盖，补齐上述三个盲区用例。
- [ ] **Step 2:** 编写「未修复问题清单」文档，内容必须包含：
  - WIP `OfflinePlayerInventory.kt` 的全部问题与修法（路径应为 `<world>/playerdata/<uuid>.dat`、需 `save()`/`NBT.modifyFile` 落盘、setItemInMainHand 双分支错误、setExtraContents 空数组越界、iterator TODO、在线竞态/原子写/DataVersion、equipment 格式仅 1.21.5+）。
  - WIP `ObservableMap.kt` 的委托绕过缺陷清单（clear/putAll/视图/迭代器/compute* 不发事件、put 替换语义、null value、线程安全）。
  - 观战者离线时状态无法还原的已知限制。
  - 未执行的低优先级建议（stateId 恒 0、ANIMATION 语义、Paper syncCommands/AsyncChatEvent 适配、closeButton 旧 API、BukkitDispatcher delay 截断、事件泛型链断裂、Conversations API 对照等），按「建议」级别罗列并附审计出处。
- [ ] **Step 3:** `./gradlew check` 全量通过（含 publish 前置校验路径） → commit `test: regression coverage for audit fixes; docs: remaining issues report`

---

### Task 9: 最终 Review

- [ ] 使用 superpowers:requesting-code-review 对分支全部变更做审查（对照本计划逐任务核对），发现问题回到对应任务修复。
- [ ] Review 通过后汇总：分支变更统计、BREAKING 清单、遗留问题文档位置，交用户决定合并方式（merge dev / PR）。

---

## Self-Review 记录

- 覆盖核对：审计「BUG-确认」31 项全部映射到 Task 2-6；「冗余-已核实」12 项中 10 项在 Task 1/Task 5（bridge 死方法复活为正确实现属于修复而非删除）、WIP 2 项按用户决定不动仅报告（Task 8）；批准的破坏性变更集中在 Task 5 Step 4/9 与 Task 7。
- 明确不做（记录在 Task 8 报告中）：overlay stateId、Paper Brigadier/AsyncChatEvent 迁移、事件泛型参数化重构（工作量大、影响面广，留待专项）。
- 类型一致性：`onDestroyed` 回调模式在 menu（`(RealChestMenu) -> Unit` 构造参数）与 overlay（`(() -> Unit)?` internal var）刻意不同——前者构造期即知 owner，后者 create 后由 manager 挂接，均为 internal 不泄漏。
