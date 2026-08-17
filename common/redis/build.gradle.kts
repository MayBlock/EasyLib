plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
    // 向 *:impl 模块的测试提供「真实 Redis」测试夹具（Testcontainers），见 src/testFixtures。
    `java-test-fixtures`
}

// 后端适配器模块，不做 api/impl 拆分——它没有面向上游插件的契约，
// 唯一的消费者是 *:impl 模块。分层规则见 CLAUDE.md「模块结构与依赖方向」，
// 由 buildsrc.convention.layering 强制执行。
dependencies {
    api(project(":common:base:api"))
    api(libs.redisson)
    implementation(project(":common:base:impl"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.slf4jApi)
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.mockk)
    testImplementation(libs.kotlinxCoroutinesTest)

    // 测试夹具：JUnit 5 扩展 + Testcontainers。kotlin("test-junit5") 在这里只为拿到 junit-jupiter-api，
    // 版本跟随 Kotlin 自身选择，与各模块 kotlin("test") 解析到的 JUnit 版本同源。
    testFixturesApi(kotlin("test-junit5"))
    testFixturesApi(libs.testcontainers)
    testFixturesImplementation(project(":common:base:impl"))
    testFixturesImplementation(libs.bundles.kotlinxEcosystem)
}

// 测试夹具只在本仓库内使用，不随模块一起发布到 JitPack。
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
