# 调度器与协程

EasyLib 通过插件持有的 `BukkitEasyLibApi` 实例提供调度器和执行上下文：

| 入口 | 类型 | 用途 |
| --- | --- | --- |
| `taskScheduler` | `TaskScheduler`（`common:base:api`） | 一次 / 延迟 / 周期任务，返回 id 可取消 |
| `getExecutionContext(BukkitExecutionContext.Sync)` | `BukkitExecutionContext.Sync`（`platform:bukkit:api`） | 普通任务和协程在主线程执行 |
| `getExecutionContext(BukkitExecutionContext.Async)` | `BukkitExecutionContext.Async`（`platform:bukkit:api`） | 普通任务和协程在 Bukkit 异步线程执行 |

```kotlin
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext

val sync = easyLib.getExecutionContext(BukkitExecutionContext.Sync)
val async = easyLib.getExecutionContext(BukkitExecutionContext.Async)
```

这里的 `Sync` / `Async` 是各接口的 companion `Key`；查询返回持有 `taskExecutor` 和 `dispatcher` 的上下文实例。获取实例或把它作为 Kotlin context 参数传入，只提供执行能力，不会自动切换当前线程。执行回调时使用 `execute`、`executeCoroutine` 或下文的调度扩展。

`BukkitEasyLib.close()` 会 `cancelAllTasks()`；协程 scope 由你自己创建、自己取消。

## TaskScheduler

```kotlin
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler.Trigger
import com.github.mayblock.easylib.base.api.scheduler.scheduleTask
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.scheduleTask
import kotlin.time.Duration.Companion.seconds

// 下一 tick 执行一次（默认 Trigger.Once，默认 executor 为 Direct = 在触发线程即主线程就地执行）
easyLib.taskScheduler.scheduleTask { println("tick") }

// 延迟 3 秒执行一次
val id = easyLib.taskScheduler.scheduleTask(Trigger.Delay(3.seconds)) { /* ... */ }
easyLib.taskScheduler.cancelTask(id)   // 到期前可取消；已执行完毕的任务返回 false

// 每 20 tick 执行一次，跑满 10 次自行取消
var n = 0
easyLib.taskScheduler.scheduleTask(Trigger.Interval(1.seconds)) {
    if (++n >= 10) cancel()        // TaskScope.cancel() 取消当前任务
}

// 指定上下文：触发时间不变，回调交给上下文的 taskExecutor 执行
easyLib.taskScheduler.scheduleTask(Trigger.Interval(5.seconds), context = async) {
    saveToDatabase()               // 在 Bukkit 异步线程执行
}
```

- `Trigger` 是值语义的 sealed interface：`Once`、`Delay(duration)`、`Interval(period)`。时长按 1 tick = 50ms 换算，不足 1 tick 按 1 tick 处理（周期任务不会以 0 tick 高频触发）。
- `Interval` 首次触发在调度后的下一 tick（初始延迟 0）。
- 一次性任务交给执行器后从注册表移除；已交给异步执行器的回调可能仍在运行，此时 `cancelTask(id)` 不会中断它。周期任务需 `cancel()` / `cancelTask(id)`，或随 `close()` 一并取消。

`scheduleTask(context = ...)` 位于 Bukkit API 层，内部复用通用层的 `scheduleTask(trigger, executor, block)`，无需依赖 impl 便捷扩展。也可以直接调用上下文的 `execute`：

```kotlin
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.execute

async.execute { heavyWork() }
sync.execute { player.teleport(loc) }   // 已在主线程时就地执行，否则排入下一 tick
```

`execute` 返回 `Unit`，异步提交时不会等待回调完成。需要等待并取得返回值时，使用挂起的 `executeCoroutine`。

## 协程 Dispatcher

`sync.dispatcher` 把协程续体投递到主线程（若当前已在主线程则不再排队，直接继续），`async.dispatcher` 投递到 Bukkit 异步线程池。两者的 `delay(...)` 都按 tick 调度；`withTimeout(...)` 的超时计时沿用协程库默认实现。

`executeCoroutine<R>` 使用 `withContext(dispatcher)`，等待代码块及其子协程完成后返回 `R`，保留异常和父协程取消的传播，并在结束后恢复调用方的协程上下文。

```kotlin
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.executeCoroutine

class MyPlugin : JavaPlugin() {
    private lateinit var easyLib: BukkitEasyLib
    private lateinit var scope: CoroutineScope

    override fun onEnable() {
        easyLib = BukkitEasyLib(this)
        val sync = easyLib.getExecutionContext(BukkitExecutionContext.Sync)
        val async = easyLib.getExecutionContext(BukkitExecutionContext.Async)
        // SupervisorJob：单个子协程失败不影响其它协程；scope 的默认调度器为主线程
        scope = CoroutineScope(SupervisorJob() + sync.dispatcher)

        scope.launch {
            val data = async.executeCoroutine { loadFromDisk() }   // 返回加载结果
            player.sendMessage(data)                               // 自动回到主线程
            delay(2.seconds)                                       // 40 tick 后继续，仍在主线程
            player.sendMessage("done")
        }
    }

    override fun onDisable() {
        scope.cancel()   // 请求取消；取消续体仍需执行 finally 等收尾代码
        easyLib.close()
    }
}
```

注意：

- Bukkit 调度器停用后不再接受任务；`cancel()` 只请求取消，不等待收尾完成。需要挂起或重新调度的收尾，应在插件和调度器仍可用时完成，不要在主线程阻塞等待投递回主线程的续体。
- `async.dispatcher` 每次 dispatch 都会开一个 Bukkit 异步任务，适合 I/O 与阻塞调用；纯 CPU 密集且高频的场景可考虑 `Dispatchers.Default`。
- `finally` 中需要挂起收尾时，可用 `withContext(NonCancellable)`；它不会延长 Bukkit 调度器的生命周期。
