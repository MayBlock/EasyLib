package com.github.mayblock.easylib.cache.impl.redis

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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisScopeLockTest {

    private val lock = mockk<RLock>()
    private val redisson = mockk<RedissonClient>()

    private fun scope(acquired: Boolean): RedisScope {
        every { lock.tryLockAsync(any<Long>(), any<TimeUnit>()) } returns
            CompletableFutureWrapper(acquired)
        every { lock.unlockAsync() } returns CompletableFutureWrapper.completedNull()
        every { redisson.getLock("L") } returns lock
        return RedisScope(redisson, NoOpMetricsRecorder)
    }

    @Test
    fun `拿到锁时执行块并释放锁`() = runTest {
        val result = scope(acquired = true).withLock("L") { "ok" }
        assertEquals("ok", result)
        verify(exactly = 1) { lock.unlockAsync() }
    }

    @Test
    fun `块抛异常时仍然释放锁`() = runTest {
        assertFailsWith<IllegalStateException> {
            scope(acquired = true).withLock("L") { error("boom") }
        }
        verify(exactly = 1) { lock.unlockAsync() }
    }

    @Test
    fun `抢锁失败时抛 IllegalStateException 且不释放锁`() = runTest {
        assertFailsWith<IllegalStateException> {
            scope(acquired = false).withLock("L") { "unreachable" }
        }
        verify(exactly = 0) { lock.unlockAsync() }
    }

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
        every { lock.tryLockAsync(any<Long>(), any<TimeUnit>()) } returns
            CompletableFutureWrapper(true)
        every { lock.unlockAsync() } returns CompletableFutureWrapper(unlockFuture)
        every { redisson.getLock("L") } returns lock

        val scope = RedisScope(redisson, NoOpMetricsRecorder)
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

    /**
     * unlockAsync().await() 跨越了一次真实的协程挂起（[CompletableFutureWrapper] 背后的
     * future 尚未完成时才恢复），kotlinx.coroutines 的 stacktrace-recovery 机制会在这种
     * 跨挂起点传播异常时拷贝出一个新的异常对象（同类型同消息，`.cause` 指回原始实例）
     * 以补全可读的调用栈。因此这里不能用 `===` 比较 unlock 异常本身，而是顺着 `.cause`
     * 链找回最初抛出的那个实例——`causeChain` 就是做这件事的。
     */
    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    /**
     * 验证 finally 中 unlock 失败不会掩盖 block 抛出的原始异常：
     * 调用方看到的必须是 block 的异常 A，而 unlock 的异常 B 只应作为 A 的 suppressed
     * 附加信息出现——而不是 B 取代 A 成为最终抛出的异常。
     *
     * 判别力：若实现退化为原始的裸 `finally { unlockAsync().await() }`（unlock 异常直接
     * 从 finally 抛出，替换掉正在传播的 block 异常），本测试的 `assertFailsWith<异常A的类型>`
     * 断言会失败（实际抛出的是 unlock 异常 B 的类型），且 A 的 suppressed 列表里也追溯不到
     * B。两个断言合起来才能把「替换」与「附加」这两种截然不同的行为区分开。
     */
    @Test
    fun `block 异常与 unlock 异常同时发生时保留原始异常并挂载 suppressed`() = runTest {
        val blockFailure = IllegalStateException("A: block failed")
        val unlockFailure = RuntimeException("B: unlock failed")

        every { lock.tryLockAsync(any<Long>(), any<TimeUnit>()) } returns
            CompletableFutureWrapper(true)
        every { lock.unlockAsync() } returns CompletableFutureWrapper<Void>(unlockFailure)
        every { redisson.getLock("L") } returns lock

        val e = assertFailsWith<IllegalStateException> {
            RedisScope(redisson, NoOpMetricsRecorder).withLock("L") {
                throw blockFailure
            }
        }

        assertTrue(e === blockFailure, "调用方应看到 block 的原始异常 A")
        assertTrue(
            e.suppressed.any { suppressed -> suppressed.causeChain().any { it === unlockFailure } },
            "unlock 的异常 B（或其 stacktrace-recovery 副本）应作为 suppressed 挂在 A 上",
        )
    }

    @Test
    fun `正常返回时 unlock 失败仍然传播`() = runTest {
        val unlockFailure = RuntimeException("unlock failed")

        every { lock.tryLockAsync(any<Long>(), any<TimeUnit>()) } returns
            CompletableFutureWrapper(true)
        every { lock.unlockAsync() } returns CompletableFutureWrapper<Void>(unlockFailure)
        every { redisson.getLock("L") } returns lock

        val e = assertFailsWith<RuntimeException> {
            RedisScope(redisson, NoOpMetricsRecorder).withLock("L") { "ok" }
        }
        assertTrue(e.causeChain().any { it === unlockFailure })
    }
}
