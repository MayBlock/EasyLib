package com.github.mayblock.easylib.redis.testing

import com.github.mayblock.easylib.base.api.metrics.MetricsRecorder
import com.github.mayblock.easylib.base.impl.metrics.NoOpMetricsRecorder
import com.github.mayblock.easylib.redis.RedisClient
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File

/**
 * 真实 Redis 测试环境的进程级单例。
 *
 * 容器在第一次被需要时启动，之后整个 JVM（同一个 Gradle test worker）内的所有测试类共用，
 * 由 Testcontainers 的 Ryuk 在 JVM 退出后回收；不逐类启停是为了让「N 个集成测试类」
 * 的成本仍然是「1 次拉起容器」。用例间隔离靠 [flushAll]，而不是靠重启容器。
 *
 * 通常不直接使用本对象，而是给测试类打 [RequiresRedis]。
 */
object RedisTestSupport {

    /** 默认镜像；可用环境变量 `EASYLIB_REDIS_IMAGE` 覆盖（例如换成 valkey 或某个具体小版本）。 */
    const val DEFAULT_IMAGE = "redis:8-alpine"

    private const val PORT = 6379

    /** 跳过标记文件路径的系统属性名，与构建约定插件 `kotlin-jvm` 约定一致。 */
    private const val SKIP_MARKER_PROPERTY = "easylib.redisSkipMarker"

    /** 为 true 时 Docker 不可用视为测试失败而非跳过；CI 上应设置，防止集成用例被静默跳过。 */
    val requireRedis: Boolean
        get() = System.getenv("EASYLIB_REQUIRE_REDIS")?.equals("true", ignoreCase = true) == true

    /** Docker 是否可用。首次求值会真实探测 daemon，之后缓存结果。 */
    val dockerAvailable: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    private val container: GenericContainer<*> by lazy {
        val image = System.getenv("EASYLIB_REDIS_IMAGE")?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE
        GenericContainer(DockerImageName.parse(image))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forListeningPort())
            .also { it.start() }
    }

    /** 容器里 Redis 的 `redis://host:port` 地址。首次访问会启动容器。 */
    val address: String
        get() = "redis://${container.host}:${container.getMappedPort(PORT)}"

    /**
     * 新建一个连到测试 Redis 的单机 [TestRedisClient]。调用方负责 [RedisClient.destroy]。
     *
     * `common:redis` 主源码只提供集群连接器（生产部署形态），这里的单机客户端仅供测试。
     */
    fun newClient(metrics: MetricsRecorder = NoOpMetricsRecorder): TestRedisClient =
        TestRedisClient(newRedisson(), metrics)

    private fun newRedisson(): RedissonClient = Redisson.create(
        Config().apply {
            useSingleServer().apply {
                this.address = this@RedisTestSupport.address
                clientName = "EasyLib-Test"
            }
        },
    )

    /** 清空容器内全部数据库，供用例间隔离。 */
    fun flushAll() {
        val result = container.execInContainer("redis-cli", "FLUSHALL")
        check(result.exitCode == 0) { "FLUSHALL failed (exit ${result.exitCode}): ${result.stderr}" }
    }

    /**
     * 记录一次「集成测试被跳过」：向 stderr 打印醒目警告（仅一次），并写下跳过标记文件。
     *
     * 警告：Gradle 控制台对 SKIPPED 用例只显示状态、不显示原因，若不额外输出，跑测试的人根本
     * 意识不到真实 Redis 那部分覆盖从未执行过。
     *
     * 标记文件：路径由系统属性 `easylib.redisSkipMarker` 给出（构建约定插件 `kotlin-jvm` 设置），
     * 构建端据此把本次 test 任务视为**不可复用**（不 up-to-date、不进构建缓存）——否则开发者
     * 之后启动了 Docker 再跑测试，Gradle 会直接复用这次的 SKIPPED 结果，集成测试永远不会真的跑。
     * 不在 Gradle 下运行（属性缺失）时只打警告。
     */
    internal fun warnSkippedOnce() {
        if (warned) return
        synchronized(this) {
            if (warned) return
            warned = true
        }
        System.getProperty(SKIP_MARKER_PROPERTY)?.let { path ->
            runCatching {
                File(path).apply {
                    parentFile?.mkdirs()
                    writeText("Redis integration tests were skipped: Docker not available\n")
                }
            }
        }
        System.err.println(
            """
            |
            |========================================================================
            |  [EasyLib] WARNING: Docker is not available — Redis integration tests
            |  (@RequiresRedis) are being SKIPPED. Real-Redis coverage did NOT run.
            |  Start Docker (Desktop) to run them, or set EASYLIB_REQUIRE_REDIS=true
            |  to turn this into a failure (recommended on CI).
            |========================================================================
            |
            """.trimMargin(),
        )
    }

    @Volatile
    private var warned = false
}
