package com.github.mayblock.easylib.cache.api

/**
 * 分布式缓存。
 *
 * 所有操作均为挂起函数——本库不提供阻塞式变体，也不提供 `runBlocking` 桥接。
 */
interface DistributedCache<K, V> {

    suspend fun get(key: K): V?
    suspend fun put(key: K, value: V)
    suspend fun remove(key: K): Boolean
}
