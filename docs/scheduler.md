# 调度器与协程

EasyLib 提供两套互补的执行工具，都挂在 `EasyLibApi.api.bukkitApi()` 上：

| 入口 | 类型 | 用途 |
| --- | --- | --- |
| `taskScheduler` | `TaskScheduler`（`common:base:api`） | 一次 / 延迟 / 周期任务，返回 id 可取消 |
| `taskExecutors` | `BukkitTaskExecutors`（`platform:bukkit:api`） | `sync` / `async` 两个 `TaskExecutor`，决定回调在哪个线程执行 |
| `dispatcher` | `BukkitDispatcher`（`platform:bukkit:api`） | `sync` / `async` 两个协程 `CoroutineDispatcher`，`delay` 按 tick 调度 |

`BukkitEasyLib.close()` 会 `cancelAllTasks()`；协程 scope 由你自己创建、自己取消。

## TaskScheduler

```kotlin
val api = EasyLibApi.api.bukkitApi()

// 下一 tick 执行一次（默认 Trigger.Once，默认 executor 为 Direct = 在触发线程即主线程就地执行）
api.taskScheduler.scheduleTask { println("tick") }

// 延迟 3 秒执行一次
val id = api.taskScheduler.scheduleTask(Trigger.Delay(3.seconds)) { /* ... */ }
api.taskScheduler.cancelTask(id)   // 到期前可取消；已执行完毕的任务返回 false

// 每 20 tick 执行一次，跑满 10 次自行取消
var n = 0
api.taskScheduler.scheduleTask(Trigger.Interval(1.seconds)) {
    if (++n >= 10) cancel()        // TaskScope.cancel() 取消当前任务
}

// 指定 executor：Bukkit 调度器只决定「何时触发」，触发后回调交给 executor 决定「在哪跑」
api.taskScheduler.scheduleTask(Trigger.Interval(5.seconds), api.taskExecutors.async) {
    saveToDatabase()               // 在 Bukkit 异步线程执行
}
```

- `Trigger` 是值语义的 sealed interface：`Once`、`Delay(duration)`、`Interval(period)`。时长按 1 tick = 50ms 换算，不足 1 tick 按 1 tick 处理（周期任务不会以 0 tick 高频触发）。
- `Interval` 首次触发在调度后的下一 tick（初始延迟 0）。
- 一次性任务执行后自动从注册表移除；周期任务需 `cancel()` / `cancelTask(id)`，或随 `close()` 一并取消。

`TaskExecutor` 本身也可直接使用：

```kotlin
api.taskExecutors.async.execute { heavyWork() }
api.taskExecutors.sync.execute { player.teleport(loc) }   // 已在主线程时就地执行，否则排入下一 tick
```

## 协程 Dispatcher

`dispatcher.sync` 把协程续体投递到主线程（若当前已在主线程则不再排队，直接继续），`dispatcher.async` 投递到 Bukkit 异步线程池；两者都实现了 `Delay`，因此 `delay(...)`、`withTimeout(...)` 会走 Bukkit 调度器按 tick 换算，而不会占用协程默认的 `DefaultDelay` 线程。

```kotlin
class MyPlugin : JavaPlugin() {
    private lateinit var scope: CoroutineScope

    override fun onEnable() {
        val api = EasyLibApi.api.bukkitApi()
        // SupervisorJob：单个子协程失败不影响其它协程；scope 的默认调度器为主线程
        scope = CoroutineScope(SupervisorJob() + api.dispatcher.sync)

        scope.launch {
            val data = withContext(api.dispatcher.async) { loadFromDisk() }   // 异步 I/O
            player.sendMessage(data)                                          // 自动回到主线程
            delay(2.seconds)                                                  // 40 tick 后继续，仍在主线程
            player.sendMessage("done")
        }
    }

    override fun onDisable() {
        scope.cancel()   // 取消所有仍在运行的协程；已排队但未执行的续体不会再执行
        // ...
    }
}
```

注意：

- 服务端关闭后 Bukkit 调度器拒绝新任务，请在 `onDisable` 中先取消 scope 再 `close()` EasyLib。
- `dispatcher.async` 每次 dispatch 都会开一个 Bukkit 异步任务，适合 I/O 与阻塞调用；纯 CPU 密集且高频的场景可考虑 `Dispatchers.Default`。
- 需要「不可取消地收尾」（例如关服前把数据写完）时用 `withContext(NonCancellable)`。
