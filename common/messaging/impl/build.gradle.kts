plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:messaging:api"))
    api(project(":common:base:api"))
    implementation(project(":common:base:impl"))
    implementation(project(":common:redis"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.slf4jApi)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatypeJsr310)
    implementation(libs.jackson.moduleKotlin)
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.mockk)
    testImplementation(libs.kotlinxCoroutinesTest)
}
