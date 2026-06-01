package com.github.mayblock.easylib.impl.feature

import com.github.mayblock.easylib.api.feature.Feature
import com.github.mayblock.easylib.api.feature.FeatureKey
import com.github.mayblock.easylib.api.feature.FeatureRegistry

class SimpleFeatureRegistry<Context>(
    private val context: Context
) : FeatureRegistry<Context> {

    private val features = mutableMapOf<FeatureKey<*>, Feature<out Context>>()

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

    override fun <FeatureContext : Context, F : Feature<FeatureContext>> uninstall(
        key: FeatureKey<in F>
    ) {
        @Suppress("UNCHECKED_CAST")
        (features.remove(key) as? F)?.also { feature ->
            feature.onUninstall(context as FeatureContext)
        } ?: throw IllegalArgumentException("Key $key is not installed")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <F : Feature<*>> getFeature(
        key: FeatureKey<in F>
    ): F? = features[key] as F?

    fun uninstallAll() {
        features.keys.toList().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            uninstall(key as FeatureKey<Feature<Context>>)
        }
    }
}