# EasyLib 文档

EasyLib 是一个面向 Bukkit/Spigot/Paper（Minecraft **26.1.2+**）的 Kotlin 库，提供命令、调度/协程、事件总线、配置委托、箱子菜单、玩家背包覆盖层、聊天输入 Prompt、自定义物品、Arena 游戏框架，以及基于 Redis 的分布式缓存与跨服消息总线。

它采用 **API / 实现分离**：你的插件可面向 `*-api` 模块编写业务代码，运行时由对应的 `*-impl` 提供实现。Bukkit 平台由插件自行构造并持有 `BukkitEasyLib`，再把所需的窄接口显式传给业务组件。

> 目标平台只支持 Minecraft 26.1.2 及以上（协议 775+），不做向下兼容。

## 目录

| 文档 | 内容 |
| --- | --- |
| [快速开始](getting-started.md) | 添加依赖、运行时接入方式、一个最小可运行插件示例（命令 + 协程调度 + 菜单） |
| [命令系统](commands.md) | 基于 Clikt 的 `BukkitCommand`：参数/选项、子命令、别名、权限、Tab 补全 |
| [调度器与协程](scheduler.md) | `TaskScheduler` / `BukkitExecutionContext`，普通任务与协程的主线程/异步执行 |
| [事件总线](events.md) | `EventBus` / `EventSource`、`on` DSL、优先级、可取消事件 |
| [配置委托](config.md) | `ConfigDelegate` 属性委托、`YamlConfig`、自动保存 |
| [箱子菜单](menus.md) | `MenuFactory` DSL：槽位、点击、取出/放入把关、显示更新、分页、事件 |
| [玩家背包覆盖层](overlay.md) | `PlayerOverlay`：在玩家背包上叠加虚拟物品并捕获交互 |
| [Prompt 与自定义物品](prompt-and-items.md) | 告示牌文本输入；带持久身份的 `CustomItem` 及其交互回调 |
| [Arena 游戏框架](arena.md) | Arena 生命周期、事件桥接、Feature / Service 与内置组件 |
| [Redis 连接](redis.md) | `SingleRedisConnector` / `ClusterRedisConnector`、`RedisClient` 与 `RedisScope` |
| [分布式缓存](distributed-cache.md) | `DistributedCache` / `RedisDistributedCache` 的装配、使用与生命周期 |
| [跨服消息总线](message-bus.md) | `MessageBus` / `RedisMessageBus` 的消息协议、订阅、投递与关闭 |

## 模块一览

依赖坐标统一为 `com.github.mayblock:EasyLib-<路径>-<api|impl>`（路径以 `-` 连接），经 [JitPack](https://jitpack.io/#MayBlock/EasyLib) 发布。

| 模块 | 坐标（artifactId） | 说明 |
| --- | --- | --- |
| `common:base:api` | `EasyLib-common-base-api` | 平台无关核心接口：`Command`、`TaskScheduler`、`EventBus`、`ConfigDelegate`、`Feature`/`Service`、`Arena` 等 |
| `common:base:impl` | `EasyLib-common-base-impl` | 上述接口的平台无关实现（`SimpleEventBus`、`YamlConfig`、`AbstractArena` 等） |
| `common:packetevents:api` / `impl` | `EasyLib-common-packetevents-api` / `-impl` | 基于 [PacketEvents](https://github.com/retrooper/packetevents) 的封包构建/发送 DSL |
| `common:cache:api` / `impl` | `EasyLib-common-cache-api` / `-impl` | 分布式缓存 `DistributedCache`（挂起接口）；impl 基于 Redis |
| `common:messaging:api` / `impl` | `EasyLib-common-messaging-api` / `-impl` | 跨服消息总线 `MessageBus`（至多一次投递）；impl 基于 Redis Pub/Sub |
| `common:redis` | `EasyLib-common-redis` | Redisson 连接层（`SingleRedisConnector` / `ClusterRedisConnector`）与 Redis DSL（后端适配器，仅供 `*-impl` 使用） |
| `platform:bukkit:api` | `EasyLib-platform-bukkit-api` | Bukkit 平台 API：`BukkitEasyLibApi`、`BukkitCommand`、菜单、覆盖层、Prompt、自定义物品、Bukkit Arena |
| `platform:bukkit:impl` | `EasyLib-platform-bukkit-impl` | Bukkit 平台实现，入口类 `BukkitEasyLib` |

**依赖方向规则**：`*-api` 只能依赖其他 `*-api`；`common:redis` 只允许被 `*-impl` 依赖。作为调用方，你通常只需要：

- 编译期：`EasyLib-platform-bukkit-api`（它已传递依赖 `EasyLib-common-base-api`）；需要缓存/消息时再加对应的 `*-api`。
- 运行期：`EasyLib-platform-bukkit-impl`（及按需的 `*-impl`），接入方式见[快速开始](getting-started.md#运行时接入)。

## 平台前置

- **Java 25**（库使用 JVM 工具链 25 编译）。
- **PacketEvents** 服务端插件（菜单、覆盖层、Prompt 等依赖它发包）；你的插件需在 `plugin.yml` 中 `depend: [packetevents]`。
- **Adventure**：Paper 自带；纯 Spigot 服务端需自行提供 `adventure-api` 与 `adventure-text-serializer-legacy`。
