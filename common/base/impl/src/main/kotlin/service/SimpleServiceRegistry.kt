package com.github.mayblock.easylib.base.impl.service

import com.github.mayblock.easylib.base.api.service.Service
import com.github.mayblock.easylib.base.api.service.ServiceKey
import com.github.mayblock.easylib.base.api.service.ServiceRegistry
import java.util.concurrent.ConcurrentHashMap

class SimpleServiceRegistry : ServiceRegistry {

    private val services = ConcurrentHashMap<ServiceKey<*>, Service>()

    @Synchronized
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

    @Synchronized
    override fun <S : Service> unregister(key: ServiceKey<in S>) {
        services.remove(key)?.onUnregister() ?: throw IllegalArgumentException("Service $key is not registered")
    }

    @Synchronized
    override fun unregisterAll() {
        services.keys.toList().forEach { key ->
            unregister(key)
        }
    }
}
