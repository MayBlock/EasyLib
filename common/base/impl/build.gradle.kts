plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:base:api"))
    implementation(libs.slf4jApi)
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.boostedYaml)
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.mockk)
    testImplementation(libs.kotlinxCoroutinesTest)
}
