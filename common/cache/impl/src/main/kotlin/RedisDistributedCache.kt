package com.github.mayblock.easylib.cache.impl

import com.github.mayblock.easylib.cache.api.DistributedCache
import com.github.mayblock.easylib.redis.RedisClient
import kotlinx.coroutines.future.await
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * 基于 Redisson `RBucket` 的分布式缓存。
 *
 * 实际 Redis 键为 `"$namespace:${keyMapper(key)}"`；值的编解码交给 Redisson 自带的
 * codec（默认 Kryo5），需要换编码请在 [RedisClient] 的 `Config` 上配置。
 *
 * [ttl] 为 null 表示永不过期。
 *
 * 三个方法都不加分布式锁：`RBucket` 的读写删本身是原子操作，为单次往返套锁只会变成
 * 「抢锁 + 操作 + 放锁」三次往返并引入锁竞争，换不到任何正确性收益。
 *
 * ## 为什么是 `RBucket` 而不是 `RMap`
 *
 * 同样的接口也可以用一个 Redis HASH 承载整个 namespace（`RMap`，或带原生逐字段 TTL 的
 * `RMapCacheNative`）。之所以选一条数据一个 key：
 *
 * - **Redis 的内存淘汰按 key 生效，不按 hash field 生效。** 一条数据一个 key 时，
 *   `allkeys-lru` 之类的策略能按冷热逐条淘汰；整个 namespace 塞进一个 hash 之后，
 *   它是单个淘汰候选——要么整体淘汰要么纹丝不动，缓存最重要的回收手段就废掉了。
 * - **一个 hash 就是一个 slot、一个节点。** 热点压在单节点、无法横向扩展、单 key 内存
 *   无上限增长、删除大 hash 会阻塞服务端、集群 resharding 需要整块搬迁。
 * - `RMapCacheNative` 的逐字段 TTL 依赖 `HEXPIRE`，要求 **Redis 7.4+**；`RBucket` 的
 *   `SET ... PX` 没有版本下限。Redis 由使用方运维，不宜由库来抬高门槛。
 *
 * 反过来，**数据集有界、逻辑成组、需要枚举或跨条目原子操作**时（如「某场对局的全部状态」），
 * hash 方案才更合适：`readAllMap` / `keySet` / `size` / `clear` 近乎免费，且同 slot 可以用
 * 一段 Lua 原子地改多个字段。[DistributedCache] 是接口，届时新增一个并列实现即可，
 * 不影响现有调用方。
 */
class RedisDistributedCache<K, V>(
    private val client: RedisClient,
    private val namespace: String,
    private val keyMapper: (K) -> String = { it.toString() },
    private val ttl: Duration? = null,
) : DistributedCache<K, V> {

    private fun redisKey(key: K): String = "$namespace:${keyMapper(key)}"

    override suspend fun get(key: K): V? = client.execute {
        withRetry(name = "cache.get") {
            withMetrics("cache.get") {
                getBucket<V>(redisKey(key)).getAsync().await()
            }
        }
    }

    override suspend fun put(key: K, value: V) {
        client.execute {
            withRetry(name = "cache.put") {
                withMetrics("cache.put") {
                    val bucket = getBucket<V>(redisKey(key))
                    if (ttl == null) bucket.setAsync(value).await()
                    else bucket.setAsync(value, ttl.toJavaDuration()).await()
                }
            }
        }
    }

    override suspend fun remove(key: K): Boolean = client.execute {
        withRetry(name = "cache.remove") {
            withMetrics("cache.remove") {
                getBucket<V>(redisKey(key)).deleteAsync().await()
            }
        }
    }
}