plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:base:api"))
    compileOnly(libs.adventure.api)
    compileOnly(libs.packetEvents.api)
}