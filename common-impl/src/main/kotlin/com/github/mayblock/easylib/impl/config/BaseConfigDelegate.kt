package com.github.mayblock.easylib.impl.config

import com.github.mayblock.easylib.api.config.ConfigDelegate
import com.github.mayblock.easylib.api.config.ConfigProperty
import com.github.mayblock.easylib.api.config.Configuration
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

abstract class BaseConfigDelegate(
    file: File,
    private val configuration: File.() -> Configuration
) : ConfigDelegate {

    private val file = file.also {
        if (!it.parentFile.exists()) it.parentFile.mkdirs()
        if (!it.exists()) it.createNewFile()
        require(it.isFile) { "Config file ${it.name} is not a file" }
        require(it.canRead() && it.canWrite()) { "Cannot read or write to config file ${it.name}" }
    }
    private var config = configuration(file)
    private val properties = mutableListOf<Property<*, *>>()

    override fun isConfigEmpty() = config.isEmpty()

    override fun <T> value(key: String, default: T & Any): Property<ConfigDelegate, T> {
        return object : Property<ConfigDelegate, T>(config) {

            override fun getValue(thisRef: ConfigDelegate, property: KProperty<*>): T {
                return config.get(key, default::class.java) ?: default.apply {
                    setValue(thisRef, property, default)
                }
            }

            override fun setValue(thisRef: ConfigDelegate, property: KProperty<*>, value: T) {
                config.set(key, value)
            }

            override val path = key
            override val default = default
        }.also(properties::add)
    }

    override fun <T> valueOrNull(key: String, type: Class<T>, default: T?): Property<ConfigDelegate, T?> {
        return object : Property<ConfigDelegate, T?>(config) {

            override fun getValue(thisRef: ConfigDelegate, property: KProperty<*>): T? {
                return config.get(key, type) ?: default?.apply {
                    setValue(thisRef, property, default)
                }
            }

            override fun setValue(thisRef: ConfigDelegate, property: KProperty<*>, value: T?) {
                config.set(key, value)
            }

            override val path = key
            override val default = default
        }.also(properties::add)

    }

    override fun <T : Enum<T>> enumValue(key: String, default: T): Property<ConfigDelegate, T> {
        return object : Property<ConfigDelegate, T>(config) {

            override fun getValue(thisRef: ConfigDelegate, property: KProperty<*>): T {
                return config.get(key, String::class.java)?.let {
                    @Suppress("UNCHECKED_CAST")
                    java.lang.Enum.valueOf(default::class.java as Class<T>, it) as T
                } ?: default.apply {
                    setValue(thisRef, property, default)
                }
            }

            override fun setValue(thisRef: ConfigDelegate, property: KProperty<*>, value: T) {
                config.set(key, value.name)
            }

            override val path = key
            override val default = default
        }.also(properties::add)
    }

    protected fun loadProperties() {
        properties.filter { config.get(it.path, Any::class.java) == null && it.default != null }.forEach {
            if (it.default!!::class.java.isEnum) {
                config.set(it.path, it.default!!::class.java.getMethod("name").invoke(it.default) as String)
            } else {
                config.set(it.path, it.default)
            }
        }
    }

    override fun reload() {
        config = configuration(file)
    }

    override fun save() = config.save()

    protected fun close() {
        config.close()
        properties.clear()
    }

    abstract inner class Property<in T, V>(val config: Configuration) : ConfigProperty<T, V> {

        fun <R> transform(toWrite: (R) -> V, toReal: (V) -> R): ReadWriteProperty<T, R> {
            return object : ReadWriteProperty<T, R> {
                override fun getValue(thisRef: T, property: KProperty<*>): R {
                    return toReal(this@Property.getValue(thisRef, property))
                }

                override fun setValue(thisRef: T, property: KProperty<*>, value: R) {
                    this@Property.setValue(thisRef, property, toWrite(value))
                }
            }
        }
    }
}