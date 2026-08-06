plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:cache:api"))
    implementation(project(":common:base:impl"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.slf4jApi)
    implementation(libs.redisson)
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.mockk)
    testImplementation(libs.kotlinxCoroutinesTest)
}