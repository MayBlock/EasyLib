// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
package buildsrc.convention

import org.gradle.api.artifacts.ProjectDependency

// 强制模块依赖方向。此前这套分层只写在 CLAUDE.md 里，没有任何东西阻止它被违反；
// 这个插件把它变成构建期错误。
//
// 规则：
//   1. `*:api` 模块只能依赖其他 `*:api` 模块。API 面必须能脱离任何实现独立编译，
//      否则「上层插件只依赖 api」这个前提就是假的。
//   2. 后端适配器（BACKEND_MODULES）只能被 `*:impl` 模块依赖。它们不做 api/impl
//      拆分，因为没有面向上游的契约；让 `*:api` 依赖它们等于把具体后端焊死进公开 API。
//
// 只校验 project 依赖，不管外部库——第三方依赖的作用域由各模块自行决定。

/** 不拆 api/impl 的后端适配器模块。 */
val backendModules = setOf(":common:redis")

/** 参与校验的依赖配置；`*Only` 与测试配置不在其列。 */
val checkedConfigurations = setOf("api", "implementation", "compileOnlyApi")

val isApiModule = project.path.endsWith(":api")

/**
 * 依赖图在配置阶段仍可被修改，因此校验必须推迟到全部 `dependencies {}` 块执行之后。
 * 这里挂在 `afterEvaluate` 上，只读依赖声明，不解析配置——解析会触发实际的构件下载。
 */
afterEvaluate {
    configurations
        .filter { it.name in checkedConfigurations }
        .forEach { configuration ->
            configuration.dependencies
                .filterIsInstance<ProjectDependency>()
                .forEach { dependency ->
                    val target = dependency.path

                    if (isApiModule && !target.endsWith(":api")) {
                        error(
                            "模块分层违规：${project.path}（api 模块）依赖了 $target。\n" +
                                "  api 模块只能依赖其他 api 模块，否则上层插件无法脱离实现编译。\n" +
                                "  配置：${configuration.name}",
                        )
                    }

                    if (target in backendModules && !project.path.endsWith(":impl")) {
                        error(
                            "模块分层违规：${project.path} 依赖了后端适配器 $target。\n" +
                                "  后端适配器只能被 *:impl 模块依赖；它没有面向上游的契约，\n" +
                                "  让其它模块依赖它会把具体后端泄漏进公开 API。\n" +
                                "  配置：${configuration.name}",
                        )
                    }
                }
        }
}
