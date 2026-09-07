package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.redisson.misc.CompletableFutureWrapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 本模块唯一保留的 mock 测试：它要验证的是「unlock 的 future 尚未完成时，取消外层协程也不能
 * 打断 finally 里的 await」——这需要一个**悬而未决**的 unlock future，真实 Redis 给不出这种
 * 状态（除非靠 `CLIENT PAUSE` 之类依赖时序的手段），所以这里用一个可手动完成的 future 代替。
 */
class RedisScopeLockCancellationTest {

    /**
     * 验证 `finally` 中 `withContext(NonCancellable) { lock.unlockAsync().await() }` 的关键性质：
     * 即使外层协程已被取消，unlock 的 `await()` 也必须真正挂起等待 Redis 端 future 完成，
     * 而不是被取消状态立即打断——否则锁会一直持有到看门狗租约超时。
     *
     * 判别手段：`unlockAsync()` 返回一个尚未完成的 [CompletableFuture]。取消外层 job 后：
     *   - 正确实现（有 NonCancellable 包裹）：await() 处于不可取消上下文，job 不会在
     *     unlock future 完成之前结束——`job.isCompleted` 仍为 false。
     *   - 错误实现（裸 await()）：await() 内部的 suspendCancellableCoroutine 发现自身
     *     上下文已取消，会立即以 CancellationException 恢复，完全不理会 unlock future
     *     是否完成——`job.isCompleted` 会在 unlock future 完成之前就变为 true。
     *
     * 这里不依赖任何真实时钟：runTest 的虚拟调度器下，`runCurrent()` 会把当前所有已就绪的
     * 协程任务跑到"稳定"为止，若此时 job 还没完成，说明它确实在等待我们手动补上的
     * unlock future，而不是被取消打断。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `协程取消后 unlock 仍必须等待 Redis 端真正完成`() = runTest {
        val unlockFuture = CompletableFuture<Void>()
        val lock = mockk<RLock>()
        every { lock.tryLockAsync(any<Long>(), any<TimeUnit>()) } returns CompletableFutureWrapper(true)
        every { lock.unlockAsync() } returns CompletableFutureWrapper(unlockFuture)
        val redisson = mockk<RedissonClient>()
        every { redisson.getLock("L") } returns lock

        val scope = DefaultRedisScope(redisson, NoOpMetricsRecorder)
        val enteredBlock = CompletableDeferred<Unit>()

        val job = launch {
            scope.withLock("L") {
                enteredBlock.complete(Unit)
                awaitCancellation()
            }
        }

        runCurrent()
        assertTrue(enteredBlock.isCompleted, "block 应已开始执行，说明锁已持有")

        job.cancel()
        runCurrent()

        // 关键断言：此时 unlock future 尚未完成，job 也绝不能已经结束。
        // 若 NonCancellable 包裹被移除，这一断言会失败（job 已被取消状态提前打断）。
        assertFalse(
            job.isCompleted,
            "unlock 尚未真正完成时 job 就已结束，说明 finally 中的 await() 被取消状态提前打断，" +
                "锁未真正释放",
        )
        verify(exactly = 1) { lock.unlockAsync() }

        unlockFuture.complete(null)
        runCurrent()

        assertTrue(job.isCompleted, "补上 unlock future 后 job 应当结束")
    }
}
