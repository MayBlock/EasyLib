package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.cache.api.CoroutineDistributedCache
import com.github.mayblock.easylib.cache.api.DistributedCache

class RedisDistributedCache<K, V>(private val client: RedisClient): DistributedCache<K, V> {

    constructor(connector: ClusterRedisConnector) : this(client = connector)

    override fun get(key: K, callback: (V?) -> Unit) {
        client.execute {
            withLock("") {
                withRetry("") {

                }
            }
        }
    }

    override fun put(key: K, value: V) {
        TODO("Not yet implemented")
    }

    override fun remove(key: K, callback: ((Boolean) -> Unit)?) {
        TODO("Not yet implemented")
    }
}

class RedisCoroutineDistributedCache<K, V>(client: RedisClient): CoroutineDistributedCache<K, V> {
    override suspend fun get(key: K): V? {

    }

    override suspend fun put(key: K, value: V) {

    }

    override suspend fun remove(key: K): Boolean {

    }
}