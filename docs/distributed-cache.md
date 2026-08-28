# 分布式缓存

EasyLib 通过 `DistributedCache<K, V>` 提供平台无关的分布式缓存接口，当前实现为 `RedisDistributedCache`。所有操作都是挂起函数，不提供阻塞式变体。

Redis 连接方式见 [Redis 连接](redis.md)。

## 添加依赖

```kotlin
dependencies {
    compileOnly("com.github.mayblock:EasyLib-common-cache-api:main-SNAPSHOT")
    implementation("com.github.mayblock:EasyLib-common-cache-impl:main-SNAPSHOT")
    implementation("com.github.mayblock:EasyLib-common-redis:main-SNAPSHOT")
}
```

业务代码可以面向 `DistributedCache` 编写；装配层使用 `RedisDistributedCache` 和 Redis 连接器提供运行时实现。

## 最小完整示例

下面的服务创建一个 Redis 连接和玩家资料缓存，提供读取、写入、删除与关闭操作：

```kotlin
data class PlayerProfile(
    val name: String,
    val level: Int,
)

class PlayerProfileCache {
    // 一个插件实例复用一个连接；不要为每次缓存操作创建新的 RedisClient。
    private val redis = SingleRedisConnector(
        host = "127.0.0.1",
        port = 6379,
        password = "secret",
        clientName = "my-plugin",
    )

    private val cache: DistributedCache<UUID, PlayerProfile> = RedisDistributedCache(
        client = redis,
        // namespace 隔离不同业务；实际键形如 myplugin:player-profile:<uuid>。
        namespace = "myplugin:player-profile",
        keyMapper = UUID::toString,
        // 每次 put 都会重置该条目的 30 分钟有效期；传 null 表示永不过期。
        ttl = 30.minutes,
    )

    // DistributedCache 只提供挂起 API，调用方应从协程中调用这些方法。
    suspend fun get(uuid: UUID): PlayerProfile? = cache.get(uuid)

    suspend fun put(uuid: UUID, profile: PlayerProfile) {
        cache.put(uuid, profile)
    }

    suspend fun remove(uuid: UUID): Boolean = cache.remove(uuid)

    fun close() {
        redis.destroy()
    }
}
```

在插件中通过可取消的协程作用域调用缓存：

```kotlin
class MyPlugin : JavaPlugin() {
    private lateinit var scope: CoroutineScope
    private lateinit var profiles: PlayerProfileCache

    override fun onEnable() {
        val api = EasyLibApi.api.bukkitApi()
        // 作用域归插件所有，停用时统一取消尚未完成的缓存操作。
        scope = CoroutineScope(SupervisorJob() + api.dispatcher.sync)
        profiles = PlayerProfileCache()

        scope.launch {
            val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")

            // put 覆盖同 key 的旧值；get 在不存在或过期时返回 null。
            profiles.put(uuid, PlayerProfile("Alex", 12))
            val profile = profiles.get(uuid)
            logger.info("Loaded profile: $profile")
            // remove 返回本次是否实际删除了缓存项。
            profiles.remove(uuid)
        }
    }

    override fun onDisable() {
        // 先停止可能仍在使用连接的协程，再关闭 RedisClient。
        scope.cancel()
        profiles.close()
    }
}
```

## 创建参数

- `client`：共享的 `RedisClient`。缓存本身不拥有连接，不会替调用方关闭它。
- `namespace`：键前缀。实际 Redis key 为 `"$namespace:${keyMapper(key)}"`。
- `keyMapper`：把业务键转换为字符串，默认调用 `toString()`。
- `ttl`：缓存有效期；`null` 表示永不过期。

## 操作语义

- `get(key)`：返回缓存值；不存在或已过期时返回 `null`。
- `put(key, value)`：写入或覆盖缓存值。
- `remove(key)`：删除缓存项；实际删除到数据时返回 `true`。
- 单次读、写、删除是原子操作，但多个方法组成的“先读后写”流程不是原子的；需要复合原子操作时在 Redis 层使用锁或脚本。
- 实现会为 `cache.get`、`cache.put`、`cache.remove` 执行重试与指标记录，但不会自动添加分布式锁。

## 序列化与键设计

- 值的编码由 Redis 连接使用的 Redisson codec 决定；默认连接器使用 Redisson 默认 codec。
- 缓存中的类发生不兼容结构变更时，旧值可能无法解码。可以更换 namespace 作为缓存版本，例如 `myplugin:v2:player-profile`。
- namespace 应包含插件或业务前缀，避免不同组件产生键冲突。
- `keyMapper` 必须稳定且无歧义；不要使用会随进程或语言环境变化的字符串形式。

## 生命周期与异常

- 所有方法都是挂起函数，应从协程调用；不要用 `runBlocking` 阻塞 Bukkit 主线程。
- 先取消仍在访问缓存的协程，再关闭共享的 `RedisClient`。
- Redis 不可用、认证失败或解码失败时，方法会在重试耗尽后抛出异常；调用方应在合适的业务边界处理失败。
