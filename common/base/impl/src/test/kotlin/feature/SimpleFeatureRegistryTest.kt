package com.github.mayblock.easylib.base.impl.feature

import com.github.mayblock.easylib.base.api.feature.Feature
import com.github.mayblock.easylib.base.api.feature.FeatureKey
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleFeatureRegistryTest {

    private class RecordingFeature(
        val id: String,
        override val dependencies: List<FeatureKey<*>> = emptyList(),
        private val log: MutableList<String>
    ) : Feature<Any> {
        override fun onInstall(context: Any) {
            log += "install:$id"
        }

        override fun onUninstall(context: Any) {
            log += "uninstall:$id"
        }
    }

    private class Key(name: String) : FeatureKey<RecordingFeature>(name)

    @Test
    fun `uninstall throws when another installed feature still depends on it`() {
        val log = mutableListOf<String>()
        val registry = SimpleFeatureRegistry<Any>(Any())
        val base = Key("base")
        val dependent = Key("dependent")

        registry.install(base) { RecordingFeature("base", log = log) }
        registry.install(dependent) { RecordingFeature("dependent", dependencies = listOf(base), log = log) }

        val error = assertFailsWith<IllegalStateException> { registry.uninstall(base) }
        assertTrue(error.message?.contains("dependent") == true, "expected message to mention dependent feature, was: ${error.message}")
    }

    @Test
    fun `uninstall succeeds when no dependents remain`() {
        val log = mutableListOf<String>()
        val registry = SimpleFeatureRegistry<Any>(Any())
        val base = Key("base")
        val dependent = Key("dependent")

        registry.install(base) { RecordingFeature("base", log = log) }
        registry.install(dependent) { RecordingFeature("dependent", dependencies = listOf(base), log = log) }

        registry.uninstall(dependent)
        registry.uninstall(base)

        assertFalse(log.contains("uninstall:base") && log.indexOf("uninstall:base") < log.indexOf("uninstall:dependent"))
        assertEquals(null, registry.getFeature(base))
        assertEquals(null, registry.getFeature(dependent))
    }

    @Test
    fun `uninstallAll removes dependents before their dependencies`() {
        val log = mutableListOf<String>()
        val registry = SimpleFeatureRegistry<Any>(Any())
        val base = Key("base")
        val mid = Key("mid")
        val top = Key("top")

        registry.install(base) { RecordingFeature("base", log = log) }
        registry.install(mid) { RecordingFeature("mid", dependencies = listOf(base), log = log) }
        registry.install(top) { RecordingFeature("top", dependencies = listOf(mid), log = log) }

        registry.uninstallAll()

        val uninstallOrder = log.filter { it.startsWith("uninstall:") }
        assertEquals(listOf("uninstall:top", "uninstall:mid", "uninstall:base"), uninstallOrder)
        assertEquals(null, registry.getFeature(base))
        assertEquals(null, registry.getFeature(mid))
        assertEquals(null, registry.getFeature(top))
    }
}
