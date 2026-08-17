# 配置委托

`ConfigDelegate`（`common:base:api`）用 Kotlin 属性委托把配置项绑定到 key，读写属性即读写配置文件。`YamlConfig`（`common:base:impl`，基于 [BoostedYAML](https://github.com/dejvokep/boosted-yaml)）是常用基类。

## 定义配置类

```kotlin
enum class Mode { SOLO, TEAM }

class ArenaConfig(file: File) : YamlConfig(file) {
    var displayName by value("display-name", "Arena")     // 带默认值，非空
    var maxPlayers  by value("max-players", 16)
    var mode        by enumValue("mode", Mode.SOLO)        // 枚举按 name 存为字符串
    var motd        by valueOrNull<String>("motd")         // 可空，缺省 null
    var spawnWorld  by valueOrNull("spawn.world", "world") // 可空但带默认值

    init {
        loadProperties()   // 把所有已声明属性的默认值写入尚未存在的 key（首次生成完整配置文件）
    }
}

val config = ArenaConfig(File(dataFolder, "arena.yml"))
config.maxPlayers = 24        // 写入配置（是否立即落盘取决于自动保存设置，见下）
println(config.mode)          // 读取
```

要点：

- 支持嵌套 key（`"spawn.world"`），按 YAML 路径解析。
- 读取时若 key 不存在，返回默认值**并把默认值写回配置**。
- `loadProperties()` 是可选的：不调用也能工作，只是缺失的 key 会等到第一次读取时才被写入。
- 值类型由默认值的运行时类型决定（`value("x", 16)` 读作 `Int`）；YAML 里类型不匹配会在读取时抛 `ClassCastException`。

## 保存、重载

```kotlin
config.save()      // 手动落盘
config.reload()    // 丢弃内存中的修改，从文件重新加载
config.isConfigEmpty()
```

## 自动保存

`YamlConfig` 有两个构造器，**默认行为不同**：

```kotlin
// (1) File 构造器：默认开启同步自动保存——每次 set/remove 后立即写文件
class A(file: File) : YamlConfig(file)

// 显式关闭自动保存
class B(file: File) : YamlConfig(file, autoSave = null)

// (2) (Path, name) 构造器：默认不自动保存，文件为 path/name.yml
class C(dir: Path) : YamlConfig(dir, "config")

// 异步（队列化）自动保存：多次快速修改合并为一次写盘，在给定 scope 的 Dispatchers.IO 上执行
class D(file: File, scope: CoroutineScope) : YamlConfig(file, autoSave = {
    scope(scope)
    onAutoSave { logger.debug("saving...") }   // 每次实际写盘前回调
})
```

自动保存只在通过委托属性 **写入** 时触发；直接修改可变对象（如往 `List` 里 add）不会被感知，需要重新赋值或手动 `save()`。

## 自定义值转换

`BaseConfigDelegate.Property.transform` 可以在委托层做双向转换（存 String、用 UUID 等）。注意要调用**成员**版本的 `value` / `valueOrNull(key, type, default)`（返回 `Property`），顶层的 reified 扩展 `valueOrNull<T>(key)` 返回的是接口类型，没有 `transform`：

```kotlin
class PlayerConfig(file: File) : YamlConfig(file) {
    var owner: UUID? by valueOrNull("owner", String::class.java, null)
        .transform(toWrite = { it?.toString() }, toReal = { it?.let(UUID::fromString) })
}
```

## 其它 Configuration 实现

`ConfigDelegate` 的读写最终落到 `Configuration` 接口（`get/set/remove/save`）。如需支持 YAML 之外的格式，继承 `BaseConfigDelegate(file) { /* File -> Configuration */ }` 并实现自己的 `Configuration`；`withAutoSave(file) { ... }` 可为任意 `Configuration` 套上自动保存。
