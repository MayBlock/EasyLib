package com.github.mayblock.easylib.api.config

interface Configuration {
    fun isEmpty(): Boolean
    fun isNull(path: String): Boolean
    fun <T> get(path: String, type: Class<T>): T?
    fun <T> set(path: String, value: T?)
    fun remove(path: String): Boolean
    fun save()
    fun close()
}

interface AutoSavable {
    fun save()
}