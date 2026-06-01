package com.github.mayblock.easylib.api.service

interface Service {
    fun onRegister()
    fun onUnregister()
}

interface ServiceRegistry {
    fun <S : Service> register(key: ServiceKey<in S>, factory: () -> S): S
    fun <S : Service> get(key: ServiceKey<in S>): S?
    fun <S : Service> unregister(key: ServiceKey<in S>)
}

fun <F : Service> ServiceRegistry.require(key: ServiceKey<F>): F =
    get(key) ?: throw IllegalStateException("Required feature ${key.name} is not installed")