package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config

class ClusterRedisConnector(
    override val addresses: List<String>,
    override val username: String? = null,
    override val password: String? = null,
    override val clientName: String = "Redis-Connector",
    override val metrics: MetricsRecorder = NoOpMetricsRecorder
): RedisConnector, RedisClient() {

    override val database: Int = -1

    constructor(
        host: String,
        port: Int,
        username: String? = null,
        password: String? = null,
        clientName: String = "Redis-Connector",
        ssl: Boolean = false
    ) : this(
        addresses = listOf("redis${if (ssl) "s" else ""}://${host}:${port}"),
        username = username,
        password = password,
        clientName = clientName
    )

    override val redisson: RedissonClient by lazy {
        Config().apply {
            useClusterServers().apply {
                apply {
                    this.clientName = this@ClusterRedisConnector.clientName
                    this.nodeAddresses = this@ClusterRedisConnector.addresses
                }
            }
            this.username = this@ClusterRedisConnector.username
            this.password = this@ClusterRedisConnector.password
        }.let(Redisson::create)
    }
}