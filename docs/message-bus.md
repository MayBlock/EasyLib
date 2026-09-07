# 跨服消息总线

`MessageBus` 用于在多个服务器或插件实例之间发送短时通知。当前实现 `RedisMessageBus` 使用 Redis Pub/Sub。

Redis 连接方式见 [Redis 连接](redis.md)。需要跨实例共享持久状态时，应使用[分布式缓存](distributed-cache.md)，不要把消息广播当作状态存储。

## 投递语义

消息总线采用 fire-and-forget 的**至多一次**投递：

- 实例宕机、重启或短暂断线期间的消息会丢失且不会补发。
- 发送失败不会自动重试，因此不会因为重试而重复投递。
- 适合全网公告、玩家切服通知、过期即失效的对局事件。
- 不适合订单、交易、审计日志等必须可靠送达的消息。

## 添加依赖

```kotlin
dependencies {
    compileOnly("com.github.mayblock:EasyLib-common-messaging-api:main-SNAPSHOT")
    implementation("com.github.mayblock:EasyLib-common-messaging-impl:main-SNAPSHOT")
    implementation("com.github.mayblock:EasyLib-common-redis:main-SNAPSHOT")
}
```

## 定义消息

每个消息类都必须使用 `@MessageType` 声明稳定的线上名称：

```kotlin
// 线上名称是跨实例协议的一部分；不兼容改动时把 v1 提升为新版本。
@MessageType("com.example.playerTransfer.v1")
data class PlayerTransfer(
    val playerId: String,
    val targetServer: String,
    val createdAt: java.time.Instant,
)
```

建议使用“反向 DNS + 语义名称 + 版本号”。重命名或移动 Kotlin 类不会改变协议；删除字段、改字段类型或增加无默认值字段时，应提升线上名称中的版本号。

payload 可以包含 JSON 基本类型、集合、嵌套 data class 和 `java.time.*`。不要直接使用 `kotlin.time.Instant` 或 `kotlin.time.Duration`，应转换为 ISO-8601 字符串或纪元数值。

## 最小完整示例

下面的服务完成 Redis 连接、消息总线创建、订阅、发布和关闭：

```kotlin
// 线上名称是跨实例协议的一部分；不兼容改动时把 v1 提升为新版本。
@MessageType("com.example.playerTransfer.v1")
data class PlayerTransfer(
    val playerId: String,
    val targetServer: String,
    val createdAt: java.time.Instant,
)

class NetworkMessages(
    private val scope: CoroutineScope,
) {
    // Redis 连接由本服务持有，并在 close() 中最后关闭。
    private val redis = SingleRedisConnector(
        host = "127.0.0.1",
        port = 6379,
        password = "secret",
        clientName = "lobby-1",
    )

    private lateinit var bus: MessageBus
    // Flow 不会自行结束，因此保存收集任务以便停用时主动取消。
    private var subscription: Job? = null

    suspend fun start() {
        // create 会注册 Redis 监听器，所以它是挂起函数。
        bus = RedisMessageBus.create(
            client = redis,
            // instanceId 用于定向寻址，必须在该服务实例重启后保持稳定。
            instanceId = "lobby-1",
            // namespace 隔离不同插件及生产/测试环境的频道。
            namespace = "myplugin",
            // 创建后立即接收发往 lobby 群组的消息。
            initialGroups = setOf("lobby"),
        )

        subscription = scope.launch {
            // subscribe 返回冷 Flow；开始 collect 后才真正消费消息。
            bus.subscribe<PlayerTransfer>().collect { envelope ->
                val message = envelope.payload
                println("${message.playerId} -> ${message.targetServer}")
            }
        }
    }

    suspend fun announceTransfer(playerId: UUID, targetServer: String) {
        bus.publish(
            // Target.All 向同一 namespace 下的所有在线实例投递。
            Target.All,
            PlayerTransfer(
                playerId = playerId.toString(),
                targetServer = targetServer,
                createdAt = java.time.Instant.now(),
            ),
        )
    }

    fun close() {
        // 顺序不可颠倒：先停止消费，再注销总线监听器，最后关闭共享 Redis 连接。
        subscription?.cancel()
        // start() 失败或尚未完成时 bus 可能还未初始化。
        if (::bus.isInitialized) bus.destroy()
        redis.destroy()
    }
}
```

在插件生命周期中启动和关闭：

```kotlin
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext

class MyPlugin : JavaPlugin() {
    private lateinit var easyLib: BukkitEasyLib
    private lateinit var scope: CoroutineScope
    private lateinit var messages: NetworkMessages

    override fun onEnable() {
        easyLib = BukkitEasyLib(this)
        // 该作用域承载消息总线的启动、订阅和发布任务。
        val async = easyLib.getExecutionContext(BukkitExecutionContext.Async)
        scope = CoroutineScope(SupervisorJob() + async.dispatcher)
        messages = NetworkMessages(scope)

        scope.launch {
            // 必须等待 start 完成监听器注册后再发布消息。
            messages.start()
            messages.announceTransfer(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "game-3",
            )
        }
    }

    override fun onDisable() {
        // 先阻止新的协程工作，再按 NetworkMessages 的内部顺序释放资源。
        scope.cancel()
        messages.close()
        easyLib.close()
    }
}
```

`start()` 必须成功完成后才能发布消息。生产代码应在启动失败时停止插件或明确进入降级状态，避免访问尚未初始化的总线。

## 投递目标

```kotlin
bus.publish(Target.All, message)
bus.publish(Target.Group("lobby"), message)
bus.publish(Target.Instance("game-3"), message)
```

- `Target.All`：投递给 namespace 下的所有在线实例。
- `Target.Group(name)`：投递给已经加入该群组的在线实例。
- `Target.Instance(id)`：投递给 `instanceId` 等于该值的在线实例。

`instanceId` 必须在实例重启后保持稳定，否则其他实例无法可靠地使用 `Target.Instance` 寻址。`namespace` 用于隔离不同插件或环境，例如生产服与测试服不应共用 namespace。

## 订阅与群组

```kotlin
val job = scope.launch {
    bus.subscribe<PlayerTransfer>(includeSelf = false).collect { envelope ->
        val message = envelope.payload
        println("id=${envelope.id}, sender=${envelope.senderId}, message=$message")
    }
}

bus.joinGroup("game-3")
bus.leaveGroup("lobby")
val currentGroups = bus.groups
```

- `subscribe` 返回冷 `Flow`，开始 `collect` 后才真正消费。
- `includeSelf = false` 是默认值，本实例不会收到自己发布的消息。
- 返回的 `Flow` 不会自行完成，`MessageBus.destroy()` 后也不会；必须取消收集它的协程。
- 动态加入群组后，已经在运行的订阅会自动开始接收该群组的消息。
- 缺少 `@MessageType` 或线上名称与另一个类冲突时，`subscribe` 会立即抛出 `IllegalArgumentException`。

## 正确关闭

插件停用时必须按顺序清理：

```kotlin
subscription.cancel()
bus.destroy()
redis.destroy()
```

先取消订阅协程，再销毁消息总线，最后关闭共享 Redis 连接。忘记调用 `bus.destroy()` 会让 Redis 客户端继续持有监听器和消息类，插件重载后可能泄漏 classloader。

`destroy()` 之后调用 `publish`、`subscribe`、`joinGroup` 或 `leaveGroup` 会抛出 `IllegalStateException`。
