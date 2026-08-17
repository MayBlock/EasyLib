package com.github.mayblock.easylib.redis.testing

import com.github.mayblock.easylib.redis.RedisClient
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

/**
 * [RequiresRedis] 背后的 JUnit 5 扩展。行为契约写在注解的文档上，这里只放实现。
 *
 * - 条件求值：Docker 不可用 → 跳过整个类并警告一次；若 [RedisTestSupport.requireRedis] 则改为抛错。
 * - beforeAll：为测试类建一个 [TestRedisClient]，挂进 Store，类结束时由 [CloseableResource] 自动 destroy。
 * - beforeEach：`FLUSHALL`。
 * - 参数注入：类型为 [RedisClient] / [TestRedisClient] 的方法/构造器参数解析为上面那个客户端。
 */
class RedisExtension : ExecutionCondition, BeforeAllCallback, BeforeEachCallback, ParameterResolver {

    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        if (RedisTestSupport.dockerAvailable) {
            return ConditionEvaluationResult.enabled("Docker available; Redis integration tests enabled")
        }
        if (RedisTestSupport.requireRedis) {
            error(
                "EASYLIB_REQUIRE_REDIS=true but Docker is not available — " +
                    "refusing to silently skip Redis integration tests",
            )
        }
        RedisTestSupport.warnSkippedOnce()
        return ConditionEvaluationResult.disabled(
            "Docker not available; skipping @RequiresRedis test " +
                "(set EASYLIB_REQUIRE_REDIS=true to fail instead)",
        )
    }

    override fun beforeAll(context: ExtensionContext) {
        // 与 Store 绑定：类级 Store 关闭时自动 close → destroy 客户端。
        context.getStore(NAMESPACE).getOrComputeIfAbsent(CLIENT_KEY) { ClientResource(RedisTestSupport.newClient()) }
    }

    override fun beforeEach(context: ExtensionContext) {
        RedisTestSupport.flushAll()
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean =
        parameterContext.parameter.type.let { it == RedisClient::class.java || it == TestRedisClient::class.java }

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any =
        extensionContext.getStore(NAMESPACE).get(CLIENT_KEY, ClientResource::class.java)?.client
            ?: error("RedisClient not initialised — is the test class annotated with @RequiresRedis?")

    private class ClientResource(val client: TestRedisClient) : CloseableResource {
        override fun close() = client.destroy()
    }

    private companion object {
        val NAMESPACE: Namespace = Namespace.create(RedisExtension::class.java)
        const val CLIENT_KEY = "client"
    }
}
