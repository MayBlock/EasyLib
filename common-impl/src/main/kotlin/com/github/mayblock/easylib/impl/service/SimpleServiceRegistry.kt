package com.github.mayblock.easylib.impl.service

import com.github.mayblock.easylib.api.service.Service
import com.github.mayblock.easylib.api.service.ServiceKey
import com.github.mayblock.easylib.api.service.ServiceRegistry

class SimpleServiceRegistry : ServiceRegistry {

    private val services = mutableMapOf<ServiceKey<*>, Service>()

    override fun <S : Service> register(
        key: ServiceKey<in S>,
        factory: () -> S
    ): S {
        require(!services.containsKey(key)) { "Service $key is already registered" }
        return factory().also { service ->
            services[key] = service
            service.onRegister()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <S : Service> get(key: ServiceKey<in S>): S? = services[key] as S?

    override fun <S : Service> unregister(key: ServiceKey<in S>) {
        services.remove(key)?.onUnregister() ?: throw IllegalArgumentException("Service $key is not registered")
    }

    fun unregisterAll() {
        services.keys.toList().forEach { key ->
            unregister(key)
        }
    }
}