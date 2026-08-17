# 快速开始

本页带你从零接入 EasyLib：添加依赖 → 在插件里启动/关闭 EasyLib → 注册一条命令、跑一个协程、打开一个菜单。示例以 Kotlin 编写，为保持简洁省略了部分 import，属于可直接对照 API 补全的伪代码级示例。

## 1. 添加依赖

EasyLib 通过 [JitPack](https://jitpack.io/#MayBlock/EasyLib) 发布，group 为 `com.github.mayblock`。仓库目前尚未打 tag，请使用 JitPack 的分支快照坐标（`main-SNAPSHOT`；也可用 `<branch>-SNAPSHOT` 或提交哈希）。

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://hub.spigotmc.org/nexus/content/groups/public/") // Spigot API
    maven("https://repo.codemc.io/repository/maven-releases/")     // PacketEvents
}

dependencies {
    // 编译期只依赖 API：脱离实现也能编译，便于按需替换/升级实现
    compileOnly("com.github.mayblock:EasyLib-platform-bukkit-api:main-SNAPSHOT")
    // 运行期实现（见下节「运行时接入」）
    implementation("com.github.mayblock:EasyLib-platform-bukkit-impl:main-SNAPSHOT")

    compileOnly("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.2")
}
```

`EasyLib-platform-bukkit-api` 会传递依赖 `EasyLib-common-base-api`（命令、调度器、事件总线、配置委托、Arena 等平台无关接口）。如需分布式缓存或跨服消息，再分别加入 `EasyLib-common-cache-{api,impl}`、`EasyLib-common-messaging-{api,impl}`。

## 2. 运行时接入

EasyLib **不是**一个独立的服务端插件，也没有 `plugin.yml`：实现类 `BukkitEasyLib` 需要由**你的插件**在 `onEnable` 中构造、在 `onDisable` 中 `close()`。构造完成后它会把自己发布到全局单例 `EasyLibApi.api`。

> **当前建议（尚未定型，后续可能调整）**：每个插件自行把 `EasyLib-platform-bukkit-impl` 及其传递依赖 shade 进自己的 jar，并 **relocate** `com.github.mayblock.easylib` 包。因为 `EasyLibApi.api` 是 JVM 级全局单例，若多个插件共用同一份未 relocate 的 EasyLib 类，后启动者会覆盖先启动者的实例。
>
> impl 还依赖 NBT-API（`de.tr7zw:item-nbt-api-plugin`），其接入方式（随插件 shade 或安装为服务端插件）待定，暂按普通传递依赖处理。

```kotlin
// build.gradle.kts（使用 shadow 插件）
plugins {
    id("com.gradleup.shadow") version "9.0.0"
}

tasks.shadowJar {
    relocate("com.github.mayblock.easylib", "your.plugin.libs.easylib")
    // Kotlin 标准库、协程、Clikt 等传递依赖同样建议 relocate，避免与其他插件冲突
}
```

服务端前置：

- **PacketEvents** 插件必须先于你的插件加载（`BukkitEasyLib` 构造时会立刻访问 `PacketEvents.getAPI()`），在 `plugin.yml` 中声明 `depend: [packetevents]`。
- **Adventure**：Paper 自带；纯 Spigot 需自行提供 `adventure-api` 与 `adventure-text-serializer-legacy`。

## 3. 最小示例

下面是一个完整的最小插件：启动 EasyLib、注册 `/hello` 命令、用协程调度器在主线程/异步线程间切换、点击命令后打开一个箱子菜单。

```yaml
# plugin.yml
name: HelloEasyLib
main: com.example.hello.HelloPlugin
version: 1.0.0
api-version: '26.1'
depend: [packetevents]
```

```kotlin
package com.example.hello

class HelloPlugin : JavaPlugin() {

    private lateinit var easyLib: BukkitEasyLib
    private lateinit var scope: CoroutineScope

    override fun onEnable() {
        // 1. 启动 EasyLib：构造完成后 EasyLibApi.api 即指向该实例
        easyLib = BukkitEasyLib(this)

        // 2. 用 EasyLib 提供的 Bukkit 调度器作为协程 Dispatcher
        val api = EasyLibApi.api.bukkitApi()
        scope = CoroutineScope(SupervisorJob() + api.dispatcher.sync)

        // 3. 注册命令
        api.commandRegistry.register(HelloCommand(scope))
    }

    override fun onDisable() {
        scope.cancel()
        // 4. 关闭 EasyLib：取消所有调度任务、销毁菜单/覆盖层、注销监听器
        easyLib.close()
    }
}
```

```kotlin
package com.example.hello

/**
 * /hello —— 命令基于 Clikt：参数/选项声明、帮助与错误提示都由 Clikt 处理。
 * playerOnly = true 时非玩家执行会被直接拒绝。
 */
class HelloCommand(private val scope: CoroutineScope) : BukkitCommand(
    name = "hello",
    description = "Open the hello menu",
    permission = "hello.use",
    playerOnly = true,
) {
    private val api get() = EasyLibApi.api.bukkitApi()

    override fun execute(sender: CommandSender) {
        val player = sender as Player
        scope.launch {
            // 主线程：发送提示
            player.sendMessage("Loading...")
            // 切到异步线程做耗时工作，再回到主线程操作 Bukkit 对象
            val greeting = withContext(api.dispatcher.async) { loadGreeting(player) }
            openMenu(player, greeting)
        }
    }

    private suspend fun loadGreeting(player: Player): String {
        delay(500)   // delay 由 EasyLib 的 Dispatcher 按 tick 换算调度
        return "Hello, ${player.name}!"
    }

    private fun openMenu(player: Player, greeting: String) {
        val menu = api.menuFactory.createChestMenu(ChestMenuType.GENERIC_9X3) {
            page(Component.text(greeting)) {
                slot(13) {
                    item(Material.DIAMOND) { setDisplayName("Click me") }
                    onClick { player.sendMessage("You clicked slot $index") }
                }
                closeButton(26)
            }
        }
        menu.open(player)
    }
}
```

要点：

- **只有 `BukkitEasyLib(this)` 这一行触碰实现类**，其余代码全部面向 `*-api`。
- `EasyLibApi.api` 是 `EasyLibApi` 类型；调用 `bukkitApi()` 拿到 `BukkitEasyLibApi`，其上暴露 `dispatcher`、`taskExecutors`、`menuFactory`、`menuRegistry`、`overlayFactory`、`promptApi`、`customItemRegistry`、`commandRegistry`、`taskScheduler`。
- 菜单 DSL 里 `onClick` 的接收者是 `SlotClickEvent`（可访问 `player`、`index`、`menu`）；回调体内不能再调用 `item(...)` 这类构建期声明——运行期改写声明是被 DSL 标记刻意禁止的。
- `close()` 会取消所有调度任务并销毁本实例创建的菜单/覆盖层，但**不会**注销命令。`CommandRegistry.unregisterAll()` 会清空服务端**整个** commandMap，请勿在插件卸载时调用；按需 `unregister(command)` 即可。

## 4. 不使用协程：TaskScheduler

不想引入协程时，可直接用 `TaskScheduler` + `TaskExecutor`：

```kotlin
val api = EasyLibApi.api.bukkitApi()

// 每秒执行一次；执行 10 次后自行取消
var count = 0
api.taskScheduler.scheduleTask(Trigger.Interval(1.seconds)) {
    if (++count >= 10) cancel()
}

// 单次延迟
api.taskScheduler.scheduleTask(Trigger.Delay(3.seconds)) {
    Bukkit.broadcastMessage("3 seconds later")
}

// 需要在异步线程执行：给 scheduleTask 指定 executor，或直接用 TaskExecutor
api.taskScheduler.scheduleTask(Trigger.Interval(5.seconds), api.taskExecutors.async) { autoSave() }
api.taskExecutors.async.execute { heavyWork() }
```

`Trigger` 有三种：`Once`（下一 tick 执行一次）、`Delay(duration)`、`Interval(period)`（时长按 tick 换算）。`scheduleTask` 返回任务 id，可用 `cancelTask(id)` 取消。详见[调度器与协程](scheduler.md)。

## 下一步

- [命令系统](commands.md)：Clikt 参数/选项/子命令、`player()` 参数转换、Tab 补全
- [调度器与协程](scheduler.md)：`TaskScheduler`、`TaskExecutor`、`BukkitDispatcher`
- [事件总线](events.md)：`EventBus` / `on { on<E> { } }` DSL
- [配置委托](config.md)：`ConfigDelegate` 与 `YamlConfig`
- [箱子菜单](menus.md)（分页、取出/放入把关、显示更新规则）与[玩家背包覆盖层](overlay.md)
- [Prompt 与自定义物品](prompt-and-items.md)
- [Arena 游戏框架](arena.md)：Feature / Service / 事件桥接
- [Redis：缓存与消息](redis.md)

完整目录见 [文档索引](README.md)。
