package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config

/**
 * 单机（非集群）Redis 连接器。集群请用 [ClusterRedisConnector]。
 */
class SingleRedisConnector(
    val address: String,
    override val database: Int = 0,
    override val username: String? = null,
    override val password: String? = null,
    override val clientName: String = "Single-Redis-Connector",
    override val metrics: MetricsRecorder = NoOpMetricsRecorder,
) : RedisConnector, RedisClient() {

    constructor(
        host: String,
        port: Int = 6379,
        database: Int = 0,
        username: String? = null,
        password: String? = null,
        clientName: String = "Single-Redis-Connector",
        ssl: Boolean = false,
        metrics: MetricsRecorder = NoOpMetricsRecorder,
    ) : this(
        address = "redis${if (ssl) "s" else ""}://$host:$port",
        database = database,
        username = username,
        password = password,
        clientName = clientName,
        metrics = metrics,
    )

    override val addresses: List<String> get() = listOf(address)

    override val redisson: RedissonClient by lazy {
        Config().apply {
            useSingleServer().apply {
                this.address = this@SingleRedisConnector.address
                this.database = this@SingleRedisConnector.database
                this.clientName = this@SingleRedisConnector.clientName
                this.username = this@SingleRedisConnector.username
                this.password = this@SingleRedisConnector.password
            }
        }.let(Redisson::create)
    }
}
