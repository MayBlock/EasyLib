plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:cache:api"))
    api(project(":common:base:api"))
    implementation(project(":common:base:impl"))
    implementation(project(":common:redis"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.slf4jApi)
    testImplementation(kotlin("test"))
    // 真实 Redis 集成测试夹具（Testcontainers）；无 Docker 时相关用例会被跳过并给出警告。
    testImplementation(testFixtures(project(":common:redis")))
}
