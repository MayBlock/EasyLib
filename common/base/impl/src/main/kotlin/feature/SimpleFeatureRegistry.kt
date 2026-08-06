package com.github.mayblock.easylib.base.impl.feature

import com.github.mayblock.easylib.base.api.feature.Feature
import com.github.mayblock.easylib.base.api.feature.FeatureKey
import com.github.mayblock.easylib.base.api.feature.FeatureRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class SimpleFeatureRegistry<Context : Any>(
    private val context: Context
) : FeatureRegistry<Context> {

    private val logger = LoggerFactory.getLogger(SimpleFeatureRegistry::class.java)

    private val features = ConcurrentHashMap<FeatureKey<*>, Feature<out Context>>()

    @Synchronized
    override fun <FeatureContext : Context, F : Feature<FeatureContext>> install(
        key: FeatureKey<in F>,
        factory: () -> F
    ): F {
        require(!features.containsKey(key)) { "Feature $key is already installed" }
        return factory().also { feature ->
            feature.dependencies.forEach { dependencyKey ->
                requireNotNull(features[dependencyKey]) {
                    "Feature ${key.name} requires ${dependencyKey.name} to be installed first"
                }
            }
            features[key] = feature
            @Suppress("UNCHECKED_CAST")
            feature.onInstall(context as FeatureContext)
        }
    }

    @Synchronized
    override fun <FeatureContext : Context, F : Feature<FeatureContext>> uninstall(
        key: FeatureKey<in F>
    ) {
        val dependents = dependentsOf(key)
        if (dependents.isNotEmpty()) {
            throw IllegalStateException(
                "Cannot uninstall $key: still required by ${dependents.joinToString { it.name }}"
            )
        }
        @Suppress("UNCHECKED_CAST")
        (features.remove(key) as? F)?.also { feature ->
            feature.onUninstall(context as FeatureContext)
        } ?: throw IllegalArgumentException("Key $key is not installed")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <F : Feature<*>> getFeature(
        key: FeatureKey<in F>
    ): F? = features[key] as F?

    /**
     * 反复卸载「当前没有其他已安装 feature 依赖」的叶子节点，直至清空，从而保证
     * 依赖方总是先于被依赖方卸载（逆拓扑序）。若剩余 feature 之间出现依赖环（无法
     * 找到任何叶子节点），则按剩余顺序强制卸载并记录警告。
     */
    @Synchronized
    override fun uninstallAll() {
        val remaining = features.keys.toMutableList()
        while (remaining.isNotEmpty()) {
            val leaves = remaining.filter { candidate -> dependentsOf(candidate, remaining).isEmpty() }
            val batch = leaves.ifEmpty {
                logger.warn("Cyclic feature dependencies detected among {}; force uninstalling in remaining order", remaining)
                listOf(remaining.first())
            }
            batch.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                (features.remove(key) as? Feature<Context>)?.onUninstall(context)
                remaining.remove(key)
            }
        }
    }

    private fun dependentsOf(
        key: FeatureKey<*>,
        candidates: Collection<FeatureKey<*>> = features.keys
    ): List<FeatureKey<*>> = candidates.filter { candidate ->
        candidate != key && key in (features[candidate]?.dependencies ?: emptyList())
    }
}
