# Redis：分布式缓存与跨服消息

三个模块协作：

| 模块 | 内容 |
| --- | --- |
| `common:redis`（`EasyLib-common-redis`） | Redisson 连接层 `RedisClient` / `SingleRedisConnector` / `ClusterRedisConnector` 与 Redis DSL `RedisScope`。**后端适配器**，无 api/impl 拆分，只应被实现层依赖 |
| `common:cache:{api,impl}` | `DistributedCache<K, V>` 挂起接口；`RedisDistributedCache` 基于 `RBucket` |
| `common:messaging:{api,impl}` | `MessageBus` 跨实例消息总线；`RedisMessageBus` 基于 Redis Pub/Sub |

```kotlin
// 编译期
compileOnly("com.github.mayblock:EasyLib-common-cache-api:main-SNAPSHOT")
compileOnly("com.github.mayblock:EasyLib-common-messaging-api:main-SNAPSHOT")
// 运行期（随插件 shade）
implementation("com.github.mayblock:EasyLib-common-cache-impl:main-SNAPSHOT")
implementation("com.github.mayblock:EasyLib-common-messaging-impl:main-SNAPSHOT")
```

两个 impl 都传递依赖 `common:redis` 与 Redisson。

## 连接：RedisClient

`RedisClient` 是抽象类：持有一个 `RedissonClient`，`execute { }` 是访问 Redis 的唯一入口，`destroy()` 关闭连接（幂等）。

```kotlin
// 单机
val client = SingleRedisConnector(host = "127.0.0.1", port = 6379, password = "secret")
val client = SingleRedisConnector("redis://127.0.0.1:6379", database = 1)

// 集群（任一节点作种子，Redisson 自行发现拓扑）
val client = ClusterRedisConnector(host = "10.0.0.1", port = 6379, password = "secret")
val client = ClusterRedisConnector(addresses = listOf("redis://10.0.0.1:6379", "redis://10.0.0.2:6379"))

// 停用插件时（在 MessageBus.destroy() 之后）
client.destroy()
```

其它部署形态（哨兵、主从等）可自行继承 `RedisClient`，用 Redisson `Config` 提供 `redisson`。

一个 `RedisClient` 通常整个插件（甚至多个插件）共用；它的寿命应长于所有基于它构建的缓存与总线。

### RedisScope DSL

`execute` 的块接收者 `RedisScope` 继承 `RedissonClient`，可直接调用 Redisson 原生 API，并提供三个可任意嵌套的装饰器：

```kotlin
val value = client.execute {                       // 默认在 Dispatchers.IO 上执行
    withLock("lock:player:$uuid", waitTime = 5.seconds) {   // 分布式锁，拿不到抛 IllegalStateException
        withRetry(times = 3, name = "load-player") {          // 总尝试次数 3；CancellationException 不重试
            withMetrics("player.load") {                      // 走 MetricsRecorder.recordSuspending
                getBucket<PlayerData>("player:$uuid").getAsync().await()
            }
        }
    }
}
```

- `withLock` 全程异步；块结束后在 `NonCancellable` 中释放锁。
- `withRetry` 不退避、不区分可重试与否的错误。
- 指标默认 `NoOpMetricsRecorder`；构造连接器时可传入自定义 `MetricsRecorder`（须覆写 `recordSuspending`，默认实现不计量）。

## 分布式缓存

```kotlin
val cache: DistributedCache<UUID, PlayerData> = RedisDistributedCache(
    client = client,
    namespace = "myplugin:player",           // 实际键为 "myplugin:player:<key>"
    keyMapper = { it.toString() },           // 默认 toString()
    ttl = 30.minutes,                        // null = 永不过期
)

scope.launch {
    cache.put(uuid, data)
    val loaded = cache.get(uuid)             // 不存在返回 null
    cache.remove(uuid)                       // 返回是否存在
}
```

- 所有操作都是挂起函数，没有阻塞变体；请在协程里调用（见[调度器与协程](scheduler.md)）。
- 值的编解码由 Redisson 的默认 codec（Kryo5）负责；要换编码请在 `RedisClient` 的 Redisson `Config` 上配置。
- 每条数据一个 Redis key（而非整个 namespace 一个 hash），便于按 key 做 LRU 淘汰与集群分片。
- 内部已带 `withRetry` + `withMetrics`（`cache.get` / `cache.put` / `cache.remove`），不加分布式锁。

## 跨服消息总线

### 语义

**fire-and-forget、至多一次**：允许丢失（实例宕机/重连期间的消息不补发），绝不重复（发送失败不重试）。适合过期作废的通知（切服、公告、对局事件）；需要可靠投递的场景不适用。查询对端状态请用缓存，消息只用于通知变更。

### 定义消息

```kotlin
@MessageType("com.example.playerTransfer.v1")      // 线上名：反向 DNS + 版本；重命名/移动类不影响协议
data class PlayerTransfer(val player: UUID, val toServer: String, val at: java.time.Instant)
```

- 消息类必须是 Jackson 可序列化的（data class、基本类型、集合、嵌套 data class、`java.time.*`）。**不支持 `kotlin.time.Instant` / `kotlin.time.Duration`**，请用 `String`/`Long` 建模。
- 不兼容改动（删字段、改类型、加必填字段）必须升版本号；新增带默认值的字段是兼容改动。
- 同一进程内两个类不能共用一个线上名。

### 创建与使用

```kotlin
// 构造是挂起的（要注册 Redis 监听器）
val bus: MessageBus = RedisMessageBus.create(
    client = client,
    instanceId = "lobby-1",                  // 必须跨重启稳定：Target.Instance 寻址依赖它
    namespace = "myplugin",                  // 隔离不同插件/环境的频道
    initialGroups = setOf("lobby"),
)

// 订阅：冷 Flow，collect 才开始消费；取消 collect 即退订。Flow 永不完成，请在可取消的 scope 里 collect
scope.launch {
    bus.subscribe<PlayerTransfer>().collect { env: Envelope<PlayerTransfer> ->
        // env.payload / env.id / env.senderId / env.time
        handle(env.payload)
    }
}

// 发布
bus.publish(Target.All, PlayerTransfer(uuid, "game-3", Instant.now()))
bus.publish(Target.Group("lobby"), msg)
bus.publish(Target.Instance("game-3"), msg)

// 动态群组
bus.joinGroup("game-3"); bus.leaveGroup("lobby")
bus.groups                                   // 当前群组快照
```

- `subscribe(includeSelf = false)`：默认不接收本实例自己发出的消息。
- `subscribe` 对缺少 `@MessageType` 或线上名冲突的类型**立即**抛 `IllegalArgumentException`。
- 慢消费者不会阻塞 Redis 线程：入站缓冲满时丢弃最旧消息（与「允许丢失」一致）。

### 必须在停用时 destroy

```kotlin
override fun onDisable() {
    scope.cancel()          // 先取消 collect 中的协程（Flow 不会自行结束）
    bus.destroy()           // 注销监听器（有界等待）、关闭 codec，切断对消息类的强引用
    client.destroy()
}
```

忘记 `destroy()` 会让长寿命的 Redis 客户端一直持有你的消息类 → 钉住插件 classloader，反复 `/reload` 会撑爆 Metaspace。`destroy()` 之后 `publish` / `subscribe` / `joinGroup` / `leaveGroup` 抛 `IllegalStateException`。

## 测试

Redis 相关测试建议跑在真实 Redis 上。本仓库通过 `common:redis` 的 test fixtures（Testcontainers `redis:8-alpine`）实现，夹具**不随模块发布**；上游可参考同样思路自行搭建。
