package com.github.mayblock.easylib.redis

import com.github.mayblock.easylib.redis.testing.RedisTestSupport
import com.github.mayblock.easylib.redis.testing.RequiresRedis
import com.github.mayblock.easylib.redis.testing.TestRedisClient
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * withLock 的锁生命周期与异常语义，跑在真实 Redis 上；「锁有没有释放」直接问 Redis。
 *
 * 「unlock 失败」不靠 mock：block 里先把锁强制释放掉，finally 里的 unlock 就会因为锁已不由
 * 本线程持有而真的失败（IllegalMonitorStateException）。
 *
 * 协程取消时的 NonCancellable 语义见 [RedisScopeLockCancellationTest]。
 */
@RequiresRedis
class RedisScopeLockTest {

    private suspend fun RedisClient.locked(name: String): Boolean =
        execute { getLock(name).isLockedAsync().await() }

    @Test
    fun `拿到锁时执行块并释放锁`(client: TestRedisClient) = runBlocking {
        val result = client.execute {
            withLock("L") {
                assertTrue(getLock("L").isLockedAsync().await(), "block 执行期间应持有锁")
                "ok"
            }
        }

        assertEquals("ok", result)
        assertFalse(client.locked("L"), "withLock 返回后锁应已释放")
    }

    @Test
    fun `块抛异常时仍然释放锁`(client: TestRedisClient) = runBlocking {
        assertFailsWith<IllegalStateException> {
            client.execute { withLock("L") { error("boom") } }
        }

        assertFalse(client.locked("L"))
    }

    @Test
    fun `抢锁失败时抛 IllegalStateException 且不动别人的锁`(client: TestRedisClient) = runBlocking {
        // 另一个客户端以另一个 threadId 持有同名锁。
        val other = RedisTestSupport.newClient()
        try {
            other.execute { getLock("L").lockAsync(30, TimeUnit.SECONDS, 4242L).await() }

            assertFailsWith<IllegalStateException> {
                client.execute { withLock("L", waitTime = 100.milliseconds) { "unreachable" } }
            }

            assertTrue(other.locked("L"), "抢锁失败不能把别人持有的锁释放掉")
        } finally {
            other.execute { getLock("L").forceUnlockAsync().await() }
            other.destroy()
        }
    }

    /**
     * unlockAsync().await() 跨越了一次真实的协程挂起，kotlinx.coroutines 的 stacktrace-recovery
     * 机制会在这种跨挂起点传播异常时拷贝出一个新的异常对象（同类型同消息，`.cause` 指回原始
     * 实例）以补全可读的调用栈。因此这里顺着 `.cause` 链找，而不是直接比较最外层实例。
     */
    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    /**
     * finally 中 unlock 失败不会掩盖 block 抛出的原始异常：调用方看到的必须是 block 的异常 A，
     * unlock 的异常 B 只应作为 A 的 suppressed 附加信息出现——而不是 B 取代 A。
     */
    @Test
    fun `block 异常与 unlock 异常同时发生时保留原始异常并挂载 suppressed`(client: TestRedisClient) = runBlocking {
        val blockFailure = IllegalStateException("A: block failed")

        val e = assertFailsWith<IllegalStateException> {
            client.execute {
                withLock("L") {
                    getLock("L").forceUnlockAsync().await() // 让随后的 unlock 真的失败
                    throw blockFailure
                }
            }
        }

        // execute 里的 withContext(Dispatchers.IO) 又是一次跨挂起点传播，所以 e 可能是 A 的 recovery 副本。
        val original = e.causeChain().firstOrNull { it === blockFailure }
        assertTrue(original != null, "调用方应看到 block 的原始异常 A（或其 recovery 副本）")
        assertTrue(
            original.suppressed.any { s -> s.causeChain().any { it is IllegalMonitorStateException } },
            "unlock 的异常应作为 suppressed 挂在 A 上",
        )
    }

    @Test
    fun `正常返回时 unlock 失败仍然传播`(client: TestRedisClient) = runBlocking {
        val e = assertFails {
            client.execute {
                withLock("L") {
                    getLock("L").forceUnlockAsync().await()
                    "ok"
                }
            }
        }

        assertTrue(e.causeChain().any { it is IllegalMonitorStateException })
    }
}
