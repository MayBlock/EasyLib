package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient

abstract class RedisClient {

    abstract val metrics: MetricsRecorder
    protected abstract val redisson: RedissonClient

    /**
     * 进入 [RedisScope] 执行 [block]，是访问 Redis 的唯一入口。
     *
     * 客户端已关闭或正在关闭时抛 [IllegalStateException]。校验只在这里做一次——
     * [RedisScope] 的装饰器不再各自重复检查。
     */
    internal suspend fun <T> execute(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend RedisScope.() -> T,
    ): T {
        check(!redisson.isShutdown && !redisson.isShuttingDown) { "Redisson client is shutdown" }
        return withContext(dispatcher) {
            RedisScope(redisson, metrics).block()
        }
    }
}
