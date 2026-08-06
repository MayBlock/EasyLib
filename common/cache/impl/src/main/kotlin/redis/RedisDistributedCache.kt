package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.cache.api.DistributedCache
import kotlin.time.Duration

class RedisDistributedCache<K, V>(
    private val client: RedisClient,
    private val namespace: String,
    private val keyMapper: (K) -> String = { it.toString() },
    private val ttl: Duration? = null,
) : DistributedCache<K, V> {

    override suspend fun get(key: K): V? = TODO("Task 7")
    override suspend fun put(key: K, value: V): Unit = TODO("Task 7")
    override suspend fun remove(key: K): Boolean = TODO("Task 7")
}