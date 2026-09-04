package com.github.mayblock.easylib.base.api.feature

interface Feature<Context : Any> {

    val dependencies: List<FeatureKey<*>> get() = emptyList()

    fun onInstall(context: Context)
    fun onUninstall(context: Context)
}

interface FeatureRegistry<in Context : Any> {
    fun <FeatureContext : Context, F : Feature<FeatureContext>> install(key: FeatureKey<in F>, factory: () -> F): F
    fun <FeatureContext : Context, F : Feature<FeatureContext>> uninstall(key: FeatureKey<in F>)
    fun <F : Feature<*>> getFeature(key: FeatureKey<in F>): F?
    fun uninstallAll()
}

fun <F : Feature<*>> FeatureRegistry<*>.require(key: FeatureKey<in F>): F =
    getFeature(key) ?: throw IllegalStateException("Required feature ${key.name} is not installed")