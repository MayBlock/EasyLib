package com.github.mayblock.easylib.impl.bukkit.overlay

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolatileMemoTest {

    @Test
    fun `未命中时计算并缓存，identity 命中不重算`() {
        val calls = AtomicInteger(0)
        val memo = VolatileMemo<String, Int> { calls.incrementAndGet(); it.length }
        val key = "abc"
        assertEquals(3, memo.get(key))
        assertEquals(3, memo.get(key)) // 同一对象：identity 命中
        assertEquals(1, calls.get())
    }

    @Test
    fun `值相等但不同实例命中缓存，不重算`() {
        val calls = AtomicInteger(0)
        val memo = VolatileMemo<String, Int> { calls.incrementAndGet(); it.length }
        memo.get(String(charArrayOf('a', 'b', 'c'))) // "abc" 实例 1（miss）
        memo.get(String(charArrayOf('a', 'b', 'c'))) // "abc" 实例 2：值相等、非同一对象
        assertEquals(1, calls.get())
    }

    @Test
    fun `key 变化触发重算`() {
        val calls = AtomicInteger(0)
        val memo = VolatileMemo<String, Int> { calls.incrementAndGet(); it.length }
        assertEquals(3, memo.get("abc"))
        assertEquals(5, memo.get("abcde"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `首次 get 前不调用 compute（惰性）`() {
        val calls = AtomicInteger(0)
        VolatileMemo<String, Int> { calls.incrementAndGet(); it.length }
        assertEquals(0, calls.get()) // 构造未触发计算
    }

    @Test
    fun `并发 get 从不抛异常且始终返回 compute(key)`() {
        // 纯确定性 compute：get(k) 无论是否命中缓存，都必须 == k*2。
        // 若缓存字段存在撕裂读/可见性 bug，会返回错的 value 或 NPE，被此不变量捕获。
        val memo = VolatileMemo<Int, Int> { it * 2 }
        val threadCount = 6
        val iterations = 20_000
        val errors = ConcurrentLinkedQueue<Throwable>()
        val pool = Executors.newFixedThreadPool(threadCount)
        val tasks = (0 until threadCount).map { t ->
            Callable {
                try {
                    for (i in 0 until iterations) {
                        val key = (i + t) % 16 // 线程间键重叠 → 缓存持续churn，最大化竞争
                        val v = memo.get(key)
                        if (v != key * 2) errors += AssertionError("get($key) 返回 $v，期望 ${key * 2}")
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        pool.invokeAll(tasks)
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        assertTrue(errors.isEmpty(), "并发错误(${errors.size})：${errors.take(3).joinToString()}")
    }
}
