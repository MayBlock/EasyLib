plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}
dependencies {
    api(project(":common-api"))
    compileOnly("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")
    compileOnly(libs.adventure.api)
    implementation(libs.bundles.kotlinxEcosystem)
}