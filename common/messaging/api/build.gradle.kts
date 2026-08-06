plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:base:api"))
    api(libs.kotlinxCoroutines)
}
