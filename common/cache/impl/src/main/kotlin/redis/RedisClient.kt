package com.github.mayblock.easylib.cache.impl.redis

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.api.util.Destroyable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient

abstract class RedisClient : Destroyable {

    abstract val metrics: MetricsRecorder
    protected abstract val redisson: RedissonClient

    /**
     * 是否已执行过 [destroy]。
     *
     * 只反映本类是否调用过 [destroy]，不代表底层 [redisson] 一定已经初始化或关闭——
     * 若 [redisson] 由 `by lazy` 提供且从未被访问过，[destroy] 不会强制触发其初始化，
     * 见 [destroy] 的说明。
     */
    override val isDestroyed: Boolean
        get() = destroyed

    @Volatile
    private var destroyed: Boolean = false

    /**
     * 关闭底层 [RedissonClient]，释放其 Netty 事件循环与连接池。
     *
     * 幂等：重复调用不会抛异常，也不会重复关闭。
     *
     * 权衡：`redisson` 通常由子类以 `by lazy` 提供。若它从未被访问过（该客户端从未
     * 执行过一次 [execute]），这里读取 `redisson` 属性本身就会触发其初始化，随即又
     * 立刻关闭它——多余但无害。之所以接受这一点而不是用反射/额外标志位去探测「lazy
     * 是否已初始化」，是因为那样会引入远比「多建一次又立刻销毁」更脆弱、更难维护的
     * 代码；这里选择正确性优先于精巧。
     */
    override fun destroy() {
        if (destroyed) return
        synchronized(this) {
            if (destroyed) return
            destroyed = true
            redisson.shutdown()
        }
    }

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
