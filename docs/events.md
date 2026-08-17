# 事件总线

`common:base:api` 提供一套轻量、类型安全的进程内事件总线，独立于 Bukkit 事件系统。菜单、覆盖层、Arena 等组件都通过它向外暴露事件；你也可以为自己的子系统创建总线。

## 核心类型

| 类型 | 说明 |
| --- | --- |
| `Event` | 事件标记接口；可取消事件同时实现 `Event.Cancellable`（`var isCancelled`） |
| `EventSource<E>` | **订阅侧**：`subscribe` / `unsubscribe` / `unsubscribeGroup` / `unsubscribeAll`，不能 emit |
| `EventBus<E>` | `EventSource<E>` + `emit(event)` |
| `EventListener<T>` | 监听器：事件类型、`group`、`priority`、处理函数 |
| `Priority` | `Int` 包装，**升序**触发（小值先）；`DEFAULT = 10`，`MONITOR = Int.MAX_VALUE`（只观察不修改） |
| `SimpleEventBus<E>` | `common:base:impl` 中的默认实现，线程安全（CopyOnWriteArrayList） |

组件对外通常只暴露 `EventSource`（例如 `Menu : EventSource<MenuEvent>`），`emit` 由实现内部持有，外部无法伪造事件。

## 订阅

推荐使用 `on` DSL：

```kotlin
val handle: Disposable = menu.on(group = "my-plugin") {
    on<MenuOpenEvent> { player.sendMessage("opened ${menu}") }
    on<SlotClickEvent>(priority = Priority(5)) { /* 先于默认优先级执行 */ }
    on<MenuCloseEvent>(priority = Priority.MONITOR) { log(player) }
}

handle.dispose()               // 一次性退订这个块里注册的全部监听器
menu.unsubscribeGroup("my-plugin")   // 或按 group 退订
```

- 处理函数的接收者就是事件本身（`T.() -> Unit`），可直接访问其属性。
- `on<T>` 按**运行时类型**过滤：订阅父类型会收到所有子类型事件。
- 单个监听器抛出的异常会被记录日志，不会中断其余监听器，也不会传播给 `emit` 调用方。

## 可取消事件

```kotlin
arena.on {
    on<ArenaJoinAttemptEvent> {
        if (arena.players.size >= 16) isCancelled = true
    }
}
```

事件由 emit 方在所有监听器执行完后检查 `isCancelled` 决定是否继续；`MONITOR` 优先级最后执行，用于观察最终结果。

## 为自己的组件创建总线

```kotlin
sealed interface ShopEvent : Event
class PurchaseEvent(val player: Player, val item: ItemStack) : ShopEvent, Event.Cancellable {
    override var isCancelled = false
}

class Shop {
    private val bus = SimpleEventBus<ShopEvent>()
    val events: EventSource<ShopEvent> get() = bus      // 对外只给订阅侧

    fun buy(player: Player, item: ItemStack) {
        val e = PurchaseEvent(player, item)
        bus.emit(e)
        if (e.isCancelled) return
        // ...
    }
}
```
