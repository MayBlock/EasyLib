package com.github.mayblock.easylib.cache.api

interface DistributedCache<K, V> {

    fun get(key: K, callback: (V?) -> Unit)
    fun put(key: K, value: V)
    fun remove(key: K, callback: ((Boolean) -> Unit)? = null)
}

interface CoroutineDistributedCache<K, V> {

    suspend fun get(key: K): V?
    suspend fun put(key: K, value: V)
    suspend fun remove(key: K): Boolean
}