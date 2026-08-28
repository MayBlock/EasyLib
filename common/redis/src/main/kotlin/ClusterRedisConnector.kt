package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config

class ClusterRedisConnector(
    override val addresses: List<String>,
    override val username: String? = null,
    override val password: String? = null,
    override val clientName: String = "Cluster-Redis-Connector",
    override val metrics: MetricsRecorder = NoOpMetricsRecorder
): RedisConnector, RedisClient() {

    override val database: Int = -1

    /**
     * 单个**集群种子节点**的便捷构造。
     *
     * 这不是「单机 Redis」入口：Redisson 会对该地址执行 `CLUSTER NODES` 自行发现整个
     * 拓扑，因此只需给出集群中任意一个可达节点。若目标是一台 `cluster-enabled no` 的
     * 独立 Redis，本类不适用。
     */
    constructor(
        host: String,
        port: Int,
        username: String? = null,
        password: String? = null,
        clientName: String = "Redis-Connector",
        ssl: Boolean = false,
        metrics: MetricsRecorder = NoOpMetricsRecorder,
    ) : this(
        addresses = listOf("redis${if (ssl) "s" else ""}://${host}:${port}"),
        username = username,
        password = password,
        clientName = clientName,
        metrics = metrics,
    )

    override val redisson: RedissonClient by lazy {
        Config().apply {
            useClusterServers().apply {
                this.clientName = this@ClusterRedisConnector.clientName
                this.nodeAddresses = this@ClusterRedisConnector.addresses
            }
            this.username = this@ClusterRedisConnector.username
            this.password = this@ClusterRedisConnector.password
        }.let(Redisson::create)
    }
}