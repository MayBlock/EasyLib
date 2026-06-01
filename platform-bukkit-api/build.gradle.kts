plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
    `java-library`
}
dependencies {
    api(project(":common-api"))
    compileOnly("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")
    implementation(libs.bundles.kotlinxEcosystem)
}