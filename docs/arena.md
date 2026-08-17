# Arena 游戏框架

Arena 是「一局游戏 / 一个房间」的抽象：持有玩家与实体集合、一套可插拔的 Feature / Service、一条事件总线，并在 Bukkit 平台上把原生事件桥接成 Arena 事件（只对属于本 Arena 的玩家生效）。

## 层次

```
Arena<Player, Entity>                 common:base:api   接口：players / entities / features / services / isArenaEnabled …
 └ AbstractArena                       common:base:impl  集合管理、enable/disable 生命周期
    └ AbstractEventfulArena            common:base:impl  叠加 EventBus<ArenaEvent>：加入/离开/生成/销毁包装成事件（可取消）
       └ AbstractBukkitArena           platform:bukkit:impl  enable 时创建 BukkitEventBridge，把 Bukkit 事件翻译成 BridgeEvent.*
```

玩家 / 实体对应 `ArenaPlayer` → `BukkitArenaPlayer` → `AbstractBukkitArenaPlayer`，`ArenaEntity` → `BukkitArenaEntity` → `AbstractBukkitArenaEntity`。

## 定义一个 Arena

```kotlin
class GamePlayer(bukkitPlayer: Player, arena: BukkitArena<*, *>) : AbstractBukkitArenaPlayer(bukkitPlayer, arena) {
    var kills = 0
}

class GameEntity(entity: Entity) : AbstractBukkitArenaEntity(entity)

class GameArena(plugin: Plugin, private val world: World) :
    AbstractBukkitArena<GamePlayer, GameEntity>("game-1", plugin),
    // 内置的 ScoreboardFeature / WaitingLobbyFeature 要求 Arena 同时是 TaskScheduler：
    // 直接委托给 EasyLib 的全局调度器即可
    TaskScheduler by EasyLibApi.api.taskScheduler {

    // 只认领本世界的实体；返回 null 表示忽略该实体的生成事件
    override fun createArenaEntity(entity: Entity): GameEntity? =
        if (entity.world == world) GameEntity(entity) else null

    override fun onEnableArena() {
        super.onEnableArena()      // 必须调用：创建 Bukkit 事件桥
        // 安装 feature / service，见下
    }

    override fun onDisableArena() { /* 自身的收尾 */ }
}
```

## 生命周期

```kotlin
val arena = GameArena(plugin, world)
arena.isArenaEnabled = true              // 触发 onEnableArena；之后才能 addPlayer / spawnEntity
arena.addPlayer(GamePlayer(player, arena))
arena.isArenaEnabled = false             // onDisableArena → 移除全部玩家/实体 → 卸载全部 feature/service → 拆桥、清空事件订阅
```

- `addPlayer` 先派发可取消的 `ArenaJoinAttemptEvent`，被取消时抛 `FailedJoinException`；成功后派发 `ArenaJoinedEvent`。`removePlayer` 派发 `ArenaLeaveEvent`。
- `spawnEntity` 同理：`ArenaEntityEvent.SpawnEvent`（可取消，取消抛 `EntitySpawnException`）/ `DestroyEvent`。
- `broadcast(message) { selector }` 向满足条件的玩家群发。

## 事件

`BukkitArena` 本身就是 `EventBus<ArenaEvent>`，用[事件总线](events.md)的 DSL 订阅。除上面的加入/离开/生成事件外，`BukkitEventBridge` 会把下列 Bukkit 事件翻译成 `BridgeEvent.*`（**仅当事件主体是本 Arena 的玩家/实体**），并把你对事件的修改/取消写回 Bukkit：

`PlayerMoveEvent`、`PlayerInteractEvent`、`BlockDamageEvent`、`PlayerDropItemEvent`、`PlayerPickupItemEvent`、`PlayerDeathEvent`、`FoodLevelChangeEvent`、`PlayerToggleSneakEvent`、`PlayerChangedWorldEvent`、`PlayerTargetedByEntityEvent`、`EntityDamageEvent` / `EntityDamageByEntityEvent` / `EntityDamageByBlockEvent`、`EntitySpawnEvent` / `CreatureSpawnEvent` / `SpawnerSpawnEvent`。

```kotlin
arena.on("game") {
    on<ArenaJoinAttemptEvent> { if (arena.players.size >= 8) isCancelled = true }
    on<BridgeEvent.PlayerMoveEvent> { if (!inBounds(to)) isCancelled = true }
    on<BridgeEvent.PlayerDeathEvent> { keepInventory = true; deathMessage = null }
}
```

Arena 禁用时会 `unsubscribeAll()`，不必手动退订。

## Feature 与 Service

两者都是「按 key 安装到 Arena 上的可插拔组件」，Arena 禁用时按依赖逆序自动卸载。

| | `Feature<Context>` | `Service` |
| --- | --- | --- |
| 生命周期 | `onInstall(arena)` / `onUninstall(arena)` | `onRegister()` / `onUnregister()` |
| 依赖声明 | `dependencies: List<FeatureKey<*>>`，安装时校验、卸载时禁止仍被依赖 | 无 |
| 注册表 | `arena.features.install(Key) { ... }` / `uninstall(Key)` / `getFeature(Key)` / `require(Key)` | `arena.services.register(Key) { ... }` / `unregister` / `get` / `require` |

Key 是伴生对象继承 `FeatureKey` / `ServiceKey` 的惯例：

```kotlin
class KillCounterFeature : Feature<GameArena> {
    companion object Key : FeatureKey<KillCounterFeature>("KillCounter")
    override val dependencies = listOf(ScoreboardFeature)          // 需要先装计分板

    private var sub: Disposable? = null
    override fun onInstall(context: GameArena) {
        sub = context.on { on<BridgeEvent.PlayerDeathEvent> { /* ... */ } }
    }
    override fun onUninstall(context: GameArena) { sub?.dispose() }
}

// 安装（通常在 onEnableArena 里）
features.install(KillCounterFeature) { KillCounterFeature() }
features.require(KillCounterFeature)          // 取回；未安装抛 IllegalStateException
```

### 内置 Feature / Service（`platform:bukkit:impl`）

| 组件 | 作用 | 备注 |
| --- | --- | --- |
| `GuardFeature(plugin, isActive, no…开关, worldGuardScope)` | 一揽子保护：禁止被怪物锁定、破坏方块、受伤、交互、丢弃/拾取、饥饿；可选 `worldGuardScope { scope { loc -> ... }; explode(false) }` 阻止范围内爆炸破坏 | 各开关默认 `true`；`isActive()` 为 false 时全部放行 |
| `PlayerJoinLeaveFeature.Server(plugin, onJoin, onQuit, onRejoin)` | 监听全服 `PlayerJoinEvent` / `PlayerQuitEvent`：非本 Arena 玩家进服 → `onJoin`，本 Arena 玩家进服 → `onRejoin`，离服 → `onQuit` | 适合「整个服务器就是一个大厅」 |
| `PlayerJoinLeaveFeature.SingleWorld(world, plugin, …)` | 同上，但以「进入/离开指定世界」为界，含 `PlayerChangedWorldEvent` | 适合按世界划分的 Arena |
| `ScoreboardFeature(period) { onView { accepts {}; title {}; lines {} } }` | 基于 FastBoard 的周期刷新计分板；多个 `onView` 按 `priority` 选第一个 `accepts` 的 | 要求 Arena 实现 `TaskScheduler`；刷新在异步线程 |
| `WaitingLobbyFeature(minPlayers, maxPlayers, playerCount, isActive, startCountdown, onComplete)` | 等待大厅倒计时：人数达标开始倒计时（标题 + 提示），不足则中止；到点回调 `onComplete` | 要求 Arena 实现 `TaskScheduler` |
| `SpectatorService(arena) { /* 观战者背包覆盖层 DSL */ }` | 观战者管理：`addSpectator` / `removeSpectator` / `getSpectator`，观战者隐身、可跟随目标 | 与 `SpectatorFeature` 配合 |
| `SpectatorFeature()` | 观战者玩家离开 Arena 时自动移除；潜行退出跟随；处理观战者的攻击/交互封包以切换跟随目标 | 要求 `SpectatorService` 已注册 |

```kotlin
override fun onEnableArena() {
    super.onEnableArena()
    services.register(SpectatorService) { SpectatorService(this) { slot(44) { item(Material.RED_BED) } } }
    features.install(SpectatorFeature) { SpectatorFeature() }
    features.install(GuardFeature) { GuardFeature(plugin, isActive = { state == State.WAITING }) }
    features.install(ScoreboardFeature) {
        ScoreboardFeature<GameArena, GamePlayer>(1.seconds) {
            onView {
                title { "§6§lGame" }
                lines { listOf("Kills: $kills", "Players: ${arena.players.size}") }
            }
        }
    }
}
```

`GuardFeature` 是 Bukkit `Listener`（用于爆炸保护），会随安装/卸载注册/注销。
