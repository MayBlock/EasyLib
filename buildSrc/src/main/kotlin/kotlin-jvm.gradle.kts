// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")
    // Apply Maven Publish plugin to enable publishing the library.
    `maven-publish`
    // 强制模块依赖方向；从这里应用，新模块无需记得手动加。
    id("buildsrc.convention.layering")
}

kotlin {
    // Use a specific Java version to make it easier to work in different environments.
    jvmToolchain(25)
}

// Single source of truth for this module's published coordinate name,
// reused by both the maven artifactId and the jar archive base name.
//
// 由 project.path 而非 project.name 推导：模块树中存在多个同名叶子项目
// （:common:base:api、:common:packetevents:api、:platform:bukkit:api 都叫 "api"），
// 取 project.name 会让它们发布到同一个 artifactId。
// 例：:platform:bukkit:impl -> "EasyLib-platform-bukkit-impl"。
val coordinateName = rootProject.name + project.path.replace(':', '-')

val rootExtra = rootProject.extra
val gitBranch = rootExtra["gitBranch"] as String
val gitCommitHash = rootExtra["gitCommitHash"] as String

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Redis 集成测试夹具（common:redis testFixtures）在 Docker 不可用而跳过用例时会写这个标记文件。
    // 一次「跳过了真实 Redis 覆盖」的测试结果不能被当成可复用的成功：否则开发者启动 Docker
    // 之后再跑测试，Gradle 会因 up-to-date / 构建缓存直接复用上次的 SKIPPED 结果，集成测试永远不跑。
    // 声明为局部变量：下面的 lambda 只能捕获 File，不能捕获脚本对象（配置缓存不允许）。
    val redisSkipMarker = project.layout.buildDirectory.file("easylib-redis-skipped.marker").get().asFile
    systemProperty("easylib.redisSkipMarker", redisSkipMarker.absolutePath)
    doFirst { redisSkipMarker.delete() }
    outputs.upToDateWhen { !redisSkipMarker.exists() }
    outputs.cacheIf("上次运行跳过了 Redis 集成测试，结果不可复用") { !redisSkipMarker.exists() }

    // Log information about all test results, not only the failed ones.
    // 同时转发测试进程的 stderr：集成测试夹具（common:redis testFixtures）在 Docker 不可用而
    // 跳过 @RequiresRedis 用例时会往 stderr 打警告，Gradle 对 SKIPPED 只显示状态不显示原因，
    // 不转发的话没人会注意到真实 Redis 那部分覆盖没跑。
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
            TestLogEvent.STANDARD_ERROR,
        )
    }
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set(coordinateName)
    archiveVersion.set(project.version.toString())
    manifest {
        attributes(
            "Implementation-Title" to coordinateName,
            "Implementation-Version" to project.version.toString(),
            "Build-Branch" to gitBranch,
            "Build-Commit" to gitCommitHash,
        )
    }
}

tasks.publish {
    dependsOn(tasks.check)
}
tasks.publishToMavenLocal {
    dependsOn(tasks.check)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // 各子项目的 project.group 带有按路径唯一化的后缀（见 settings.gradle.kts），
            // 对外发布必须统一回根项目的 group（com.github.mayblock，JitPack 要求）。
            groupId = rootProject.group as String
            artifactId = coordinateName
            version = project.version as String
        }
    }
}
