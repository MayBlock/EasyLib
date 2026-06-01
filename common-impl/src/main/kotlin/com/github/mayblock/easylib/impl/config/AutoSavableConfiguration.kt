package com.github.mayblock.easylib.impl.config

import com.github.mayblock.easylib.api.config.AutoSavable
import com.github.mayblock.easylib.api.config.Configuration
import com.github.mayblock.easylib.impl.util.extension.ifTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

internal class QueuedAutoSaveConfiguration(
    delegate: Configuration,
    file: File,
    scope: CoroutineScope,
    onAutoSave: (() -> Unit)? = null
) : AutoSaveConfiguration(delegate, file, onAutoSave) {

    private val saveChannel = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in saveChannel) {
                super.save()
            }
        }
    }

    override fun save() {
        saveChannel.trySend(Unit)
    }
}

internal open class AutoSaveConfiguration(
    protected val delegate: Configuration,
    protected val file: File,
    protected val onAutoSave: (() -> Unit)? = null
) : Configuration by delegate, AutoSavable {

    final override fun <T> set(path: String, value: T?) {
        delegate.set(path, value)
        save()
    }

    final override fun remove(path: String): Boolean = delegate.remove(path).ifTrue {
        save()
    }

    override fun save() {
        onAutoSave?.invoke()
        delegate.save()
    }
}

@DslMarker
annotation class AutoSaveDsl

@AutoSaveDsl
class AutoSaveConfigurationBuilder internal constructor(private val file: File) {
    private var onAutoSave: (() -> Unit)? = null
    private var scope: CoroutineScope? = null

    fun onAutoSave(block: () -> Unit) {
        this.onAutoSave = block
    }

    fun scope(scope: CoroutineScope) {
        this.scope = scope
    }

    internal fun build(delegate: Configuration): AutoSaveConfiguration {
        return if (scope != null) {
            QueuedAutoSaveConfiguration(
                delegate,
                file,
                scope!!,
                onAutoSave
            )
        } else AutoSaveConfiguration(
            delegate,
            file,
            onAutoSave
        )
    }
}

fun Configuration.withAutoSave(file: File, block: AutoSaveConfigurationBuilder.() -> Unit): Configuration =
    AutoSaveConfigurationBuilder(file).apply(block).build(this)