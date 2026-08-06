package com.github.mayblock.easylib.redis

import org.redisson.api.RedissonClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Redis 操作的 DSL 作用域。
 *
 * 继承 [RedissonClient]，因此块内可以直接调用 `getBucket`、`getMap`、`getTopic` 等原生
 * API；三个装饰器的块参数 receiver 同样是 [RedisScope]，因此可以任意顺序、任意层数嵌套：
 *
 * ```
 * client.execute {
 *     withLock("lock:$key") {
 *         withRetry(3) {
 *             withMetrics("cache.get") {
 *                 getBucket<V>(key).getAsync().await()
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * 实例由 [RedisClient.execute] 提供；实现类不对外暴露。
 */
interface RedisScope : RedissonClient {

    /**
     * 在分布式锁 [name] 的保护下执行 [block]，最多等待 [waitTime] 获取锁。
     *
     * 全程使用 Redisson 的异步 API，不阻塞线程——阻塞版在 `Dispatchers.IO` 上抢锁
     * 会白占一个线程最长 [waitTime]。
     *
     * 获取失败抛 [IllegalStateException]（此时不会尝试释放）。释放动作在
     * `NonCancellable` 中执行，避免协程被取消时锁泄漏到看门狗租约到期；若释放本身也
     * 失败，该异常会作为 suppressed 挂在 [block] 抛出的原始异常上，不会把它顶替掉。
     *
     * 已知窗口：若协程在 Redis 已授予锁、但 `tryLockAsync` 的 await 尚未返回时被取消，
     * 这把锁不会被显式释放，只能等看门狗租约到期。收窄它需要把获取动作也放进
     * `NonCancellable`，代价是换来一段最长 [waitTime] 不可取消的等待，不划算。
     */
    suspend fun <T> withLock(
        name: String,
        waitTime: Duration = 5.seconds,
        block: suspend RedisScope.() -> T,
    ): T

    /**
     * 失败重试。[times] 是**总尝试次数**，不是首次之外的重试次数——`withRetry(3)` 最多执行
     * [block] 三次。末次仍失败则原样抛出该次异常。
     *
     * `CancellationException` 一律直接上抛，不计入重试：协程取消不是可重试的失败。
     *
     * 重试不加退避，也不区分可重试与不可重试的失败——codec 解码失败、`WRONGTYPE`、认证
     * 错误这类必然失败的情况同样会被重试满 [times] 次。
     *
     * [name] 用于标识重试日志所属的操作，便于在并发调用时区分不同来源的重试行。
     */
    suspend fun <T> withRetry(
        times: Int = 3,
        name: String? = null,
        block: suspend RedisScope.() -> T,
    ): T

    /**
     * 用 `MetricsRecorder` 包裹 [block]，以 [operation] 为指标名。
     *
     * 走 `MetricsRecorder.recordSuspending`；注意该方法的默认实现**不计量**，
     * 上游若使用自定义 recorder 而未覆写它，这里的指标会静默丢失。
     */
    suspend fun <T> withMetrics(
        operation: String,
        block: suspend RedisScope.() -> T,
    ): T
}
