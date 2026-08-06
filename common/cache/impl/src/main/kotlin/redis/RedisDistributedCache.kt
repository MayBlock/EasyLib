package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.cache.api.DistributedCache
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
