package com.github.mayblock.easylib.api.config

import kotlin.properties.ReadWriteProperty

inline fun <reified T> ConfigDelegate.value(key: String) =
    value(key, T::class.java)

inline fun <reified T> ConfigDelegate.valueOrNull(key: String, default: T? = null) =
    valueOrNull(key, T::class.java, default)

interface ConfigDelegate {
    fun isConfigEmpty(): Boolean
    fun reload()
    fun save()
    fun <T> value(key: String, default: T & Any): ConfigProperty<ConfigDelegate, T>
    fun <T> valueOrNull(key: String, type: Class<T>, default: T?): ConfigProperty<ConfigDelegate, T?>
    fun <T : Enum<T>> enumValue(key: String, default: T): ConfigProperty<ConfigDelegate, T>
}

interface ConfigProperty<in T, V> : ReadWriteProperty<T, V> {
    val path: String
    val default: V?
}