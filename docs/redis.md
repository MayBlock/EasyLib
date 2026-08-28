# Redis 连接

本页只介绍 EasyLib 的 Redis 连接层：如何添加依赖、创建连接、执行 Redis 操作以及安全关闭连接。

分布式缓存与跨服消息总线分别见：

- [分布式缓存](distributed-cache.md)
- [跨服消息总线](message-bus.md)

## 添加依赖

`common:redis` 是基于 Redisson 的后端适配器，不拆分 API / 实现模块。只有需要直接创建 Redis 连接或执行 Redis 操作的实现层需要依赖它。

```kotlin
dependencies {
    implementation("com.github.mayblock:EasyLib-common-redis:main-SNAPSHOT")
}
```

## 连接单机 Redis

使用 `SingleRedisConnector` 连接普通单机 Redis：

```kotlin
val redis = SingleRedisConnector(
    host = "127.0.0.1",
    port = 6379,
    database = 0,
    username = null,
    password = "secret",
    clientName = "my-plugin",
)
```

也可以直接传入完整地址：

```kotlin
val redis = SingleRedisConnector(
    address = "redis://127.0.0.1:6379",
    database = 0,
    password = "secret",
)
```

启用 TLS 时可以设置 `ssl = true`，或使用 `rediss://` 地址。

## 连接 Redis Cluster

使用 `ClusterRedisConnector` 连接启用了 Redis Cluster 的集群。可以传入一个可达节点，Redisson 会从该节点发现集群拓扑：

```kotlin
val redis = ClusterRedisConnector(
    host = "10.0.0.1",
    port = 6379,
    password = "secret",
    clientName = "my-plugin",
)
```

也可以提供多个种子节点：

```kotlin
val redis = ClusterRedisConnector(
    addresses = listOf(
        "redis://10.0.0.1:6379",
        "redis://10.0.0.2:6379",
    ),
    password = "secret",
)
```

`ClusterRedisConnector` 只能连接启用了集群模式的 Redis，不能用于普通单机 Redis。Redis Cluster 不支持选择逻辑数据库，因此该连接器的 `database` 固定为 `-1`。

哨兵、主从等其它部署方式目前没有内置连接器；可以继承 `RedisClient`，通过 Redisson `Config` 提供对应的 `RedissonClient`。

## 执行 Redis 操作

所有 Redis 操作都应从 `RedisClient.execute { }` 进入。代码块的接收者是 `RedisScope`，可直接调用 Redisson 的异步 API：

```kotlin
suspend fun loadPlayerName(redis: RedisClient, uuid: UUID): String? =
    redis.execute {
        getBucket<String>("player:$uuid:name").getAsync().await()
    }
```

`execute` 默认在 `Dispatchers.IO` 上执行，也可以显式传入其它协程调度器。

### 锁、重试与指标

`RedisScope` 提供三个可以任意嵌套的装饰器：

```kotlin
val profile = redis.execute {
    withLock("lock:player:$uuid", waitTime = 5.seconds) {
        withRetry(times = 3, name = "load-player") {
            withMetrics("player.load") {
                getBucket<PlayerProfile>("player:$uuid").getAsync().await()
            }
        }
    }
}
```

- `withLock`：异步获取分布式锁；在等待时间内未取得锁时抛出 `IllegalStateException`。
- `withRetry`：`times` 表示总尝试次数；不包含退避策略，也不会重试协程取消。
- `withMetrics`：通过连接器的 `MetricsRecorder` 记录操作；默认 recorder 不采集指标。

## 关闭连接

连接器持有网络连接和事件循环，插件停用时必须调用 `destroy()`：

```kotlin
class RedisService {
    private val redis = SingleRedisConnector("redis://127.0.0.1:6379")

    suspend fun read(key: String): String? = redis.execute {
        getBucket<String>(key).getAsync().await()
    }

    fun close() {
        redis.destroy()
    }
}
```

`destroy()` 是幂等的。关闭后再次调用 `execute` 会抛出 `IllegalStateException`。

如果多个组件共享同一个 `RedisClient`，应由创建连接的组件统一管理生命周期。关闭顺序为：先停止使用连接的任务、订阅和消息总线，再关闭 `RedisClient`。

## 注意事项

- 不要为每次请求创建新的连接器；通常每个插件实例创建并复用一个 `RedisClient`。
- `redis://` 是普通连接，`rediss://` 是 TLS 连接。
- `SingleRedisConnector` 可以选择逻辑数据库；Redis Cluster 不支持该能力。
- 密码、用户名和地址应来自配置文件或环境变量，不要写死在源码中。
- `withRetry` 会重试包括认证失败、解码失败在内的所有普通异常；只对确实可能恢复的操作使用它。
- 自定义指标记录器需要覆写 `recordSuspending`，否则 `withMetrics` 不会产生实际指标。
