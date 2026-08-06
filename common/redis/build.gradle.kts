plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
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
}
