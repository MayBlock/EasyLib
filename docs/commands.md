# 命令系统

EasyLib 的命令建立在 [Clikt](https://ajalt.github.io/clikt/) 之上：参数/选项声明、解析、帮助文本、错误提示都由 Clikt 完成，EasyLib 只负责把它挂到 Bukkit 的 commandMap 上并做 Tab 补全。

相关类型：

| 类型 | 模块 | 说明 |
| --- | --- | --- |
| `Command` | `common:base:api` | 平台无关基类，继承 Clikt 的 `CoreCliktCommand` |
| `BukkitCommand` | `platform:bukkit:api` | Bukkit 版基类：权限、仅玩家、`execute(sender)`、`player()` 参数转换 |
| `CommandRegistry` | `common:base:api` | 注册/注销；经 `EasyLibApi.api.commandRegistry` 取得 |

> Bukkit 平台的注册表只接受 `BukkitCommand` 子类，传入其它 `Command` 会被跳过并打警告。

## 定义命令

```kotlin
class GiveCoinsCommand : BukkitCommand(
    name = "givecoins",
    description = "Give coins to a player",   // 出现在 /help 与 Clikt 帮助中
    permission = "economy.give",              // 无权限时直接拒绝，不进入解析
    playerOnly = false,                       // true 时控制台执行会被拒绝
) {
    // BukkitCommand 提供的参数转换：按名字查在线玩家，找不到时给出 Clikt 风格报错；Tab 补全为在线玩家名
    private val target by player()
    private val amount by argument().int()
    private val silent by option("--silent", "-s").flag()

    override fun execute(sender: CommandSender) {
        economy.deposit(target, amount)
        if (!silent) echo("Gave $amount coins to ${target.name}")   // echo 会发给 sender
    }
}
```

要点：

- 参数/选项声明用 Clikt 原生 API（`argument()`、`option()`、`.int()`、`.flag()`、`.multiple()` 等）。
- `echo(...)` 已被重定向到 `sender.sendMessage`；`echo(err = true)` 会加红色。
- 解析失败（参数缺失、类型错误、`--help`）时，Clikt 的错误/帮助文本会以红色发送给 sender。
- `execute` 中抛出的非 Clikt 异常会被记录到日志并打印堆栈，不会传播到 Bukkit。

## 子命令

直接使用 Clikt 的 `subcommands`：

```kotlin
class ArenaCommand : BukkitCommand("arena", "Arena management") {
    init { subcommands(ArenaCreate(), ArenaDelete()) }
    override fun execute(sender: CommandSender) { echo(getFormattedHelp() ?: "") }
}

class ArenaCreate : BukkitCommand("create", playerOnly = true) {
    private val name by argument()
    override fun execute(sender: CommandSender) { /* ... */ }
}
```

`sender` 会随 Clikt 的 context 传递给子命令，子命令同样通过 `execute(sender)` 拿到它。

## 别名

覆写 `aliases()`。它是 Clikt 的「别名 → token 列表」映射；EasyLib 会把每个 key 注册为 Bukkit 别名，执行时把 value 列表**去掉第一个元素**后作为前缀拼到实际参数前：

```kotlin
override fun aliases() = mapOf(
    "ac" to listOf("arena", "create"),   // /ac foo  ⇒  /arena create foo
)
```

## 注册与注销

```kotlin
val registry = EasyLibApi.api.commandRegistry
registry.register(GiveCoinsCommand(), ArenaCommand())   // 已存在同名命令时跳过并打警告
registry.isRegistered("arena")
registry.unregister("arena")
```

> **不要在插件卸载时调用 `unregisterAll()`**：当前实现会清空服务端**整个** commandMap（包括其它插件与原版命令）。请按名字逐个 `unregister`。`BukkitEasyLib.close()` 也不会自动注销命令。

## Tab 补全

补全由 EasyLib 按 Clikt 命令树递归解析：光标处依次考虑子命令名、选项名、选项值、位置参数值。候选来源是参数/选项的 `completionCandidates`：

- `CompletionCandidates.Fixed(...)` → 固定列表；
- `CompletionCandidates.Username` → 在线玩家名（`player()` 参数默认使用）；
- 其它类型暂无候选。

```kotlin
private val mode by argument(completionCandidates = CompletionCandidates.Fixed(setOf("solo", "team")))
```
