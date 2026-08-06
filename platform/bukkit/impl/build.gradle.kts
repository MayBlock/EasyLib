plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":platform:bukkit:api"))
    implementation(project(":common:base:impl"))
    implementation(project(":common:packetevents:impl"))
    implementation(libs.slf4jApi)
    compileOnly("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")
    compileOnly(libs.packetEvents.spigot)
    compileOnly(libs.adventure.serializer.legacy)
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.nbtApi)
    implementation(libs.fastboard)
    implementation(libs.caffeine)
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.mockk)
    // Paper API supersedes spigot-api for test runtime; they conflict as capabilities.
    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.packetEvents.spigot)
}


