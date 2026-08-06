plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:packetevents:api"))
    compileOnly(libs.adventure.api)
    implementation(libs.packetEvents.spigot)
}