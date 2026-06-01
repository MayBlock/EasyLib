package com.github.mayblock.easylib.impl.config

import com.github.mayblock.easylib.api.config.Configuration
import dev.dejvokep.boostedyaml.YamlDocument
import java.io.File
import java.nio.file.Path

abstract class YamlConfig(
    file: File,
    autoSave: (AutoSaveConfigurationBuilder.() -> Unit)? = {}
) : BaseConfigDelegate(file, {
    file.loadYamlConfig().let {
        if (autoSave != null) {
            it.withAutoSave(file, autoSave)
        } else it
    }
}) {

    constructor(
        path: Path,
        name: String,
        autoSave: (AutoSaveConfigurationBuilder.() -> Unit)? = null,
    ) : this(File(path.toFile(), "$name.yml"), autoSave)
}

class YamlConfiguration(val yml: YamlDocument) : Configuration {

    override fun isEmpty() = yml.isEmpty(true)
    override fun isNull(path: String) = yml.get(path) == null

    override fun <T> get(path: String, type: Class<T>): T? = type.cast(yml.get(path))
    override fun <T> set(path: String, value: T?) {
        yml.set(path, value)
    }

    override fun remove(path: String): Boolean = yml.remove(path)
    override fun save() {
        yml.save()
    }

    override fun close() {}
}

private fun File.loadYamlConfig(): YamlConfiguration {
    return YamlDocument.create(this).let(::YamlConfiguration)
}

