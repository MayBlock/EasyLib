package com.github.mayblock.easylib.redis.testing

import org.junit.jupiter.api.extension.ExtendWith

/**
 * 标记一个测试类需要**真实的 Redis**（由 Testcontainers 启动的容器）。
 *
 * 用法：
 * ```
 * @RequiresRedis
 * class FooTest {
 *     @Test
 *     fun `...`(client: TestRedisClient) = runBlocking { ... }
 * }
 * ```
 * - 测试方法（或构造器）可以直接声明 [TestRedisClient]（或其父类型 [com.github.mayblock.easylib.redis.RedisClient]）
 *   参数，由扩展注入；该客户端连到容器里的 Redis，整个测试类共用一个实例，类结束时自动 destroy。
 *   [TestRedisClient.hooks] 提供调用计数、监听器捕获与故障注入。需要更多客户端（例如自定义
 *   [com.github.mayblock.easylib.base.api.metrics.MetricsRecorder]）时用 [RedisTestSupport.newClient]。
 * - 每个测试方法执行前容器会被 `FLUSHALL`，用例之间互不残留数据。
 * - 本机没有可用的 Docker 时，整个测试类**跳过**并向 stderr 打印警告；
 *   设置环境变量 `EASYLIB_REQUIRE_REDIS=true`（CI 场景）可改为直接失败，防止集成用例被静默跳过。
 * - 镜像默认见 [RedisTestSupport.DEFAULT_IMAGE]，可用环境变量 `EASYLIB_REDIS_IMAGE` 覆盖。
 *
 * 用 [kotlinx.coroutines.runBlocking] 写这些用例，而不是 `runTest`——被测的是真实 I/O 与真实时钟。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(RedisExtension::class)
annotation class RequiresRedis
