plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
    `java-library`
}
dependencies {
    api(project(":common-api"))
    compileOnlyApi(libs.packetEvents.api)
}