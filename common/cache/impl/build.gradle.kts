plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:cache:api"))
    implementation(project(":common:base:impl"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation("org.redisson:redisson:4.6.1")
}