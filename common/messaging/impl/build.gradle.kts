plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:messaging:api"))
    // Redis 访问一律经由 :common:redis，不直接依赖 Redisson——版本归 version catalog 管，
    // 且后端适配器只允许 *:impl 依赖（见 CLAUDE.md「模块类型与分层规则」）。
    implementation(project(":common:redis"))
}