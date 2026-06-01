package com.github.mayblock.easylib.api.feature

interface Feature<Context> {

    val dependencies: List<FeatureKey<*>> get() = emptyList()

    fun onInstall(context: Context)
    fun onUninstall(context: Context)
}

interface FeatureRegistry<Context> {
    fun <FeatureContext : Context, F : Feature<FeatureContext>> install(key: FeatureKey<in F>, factory: () -> F): F
    fun <FeatureContext : Context, F : Feature<FeatureContext>> uninstall(key: FeatureKey<in F>)
    fun <F : Feature<*>> getFeature(key: FeatureKey<in F>): F?
}

fun <F : Feature<*>> FeatureRegistry<*>.require(key: FeatureKey<F>): F =
    getFeature(key) ?: throw IllegalStateException("Required feature ${key.name} is not installed")