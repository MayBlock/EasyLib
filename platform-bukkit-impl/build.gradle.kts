plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":platform-bukkit-api"))
    implementation(project(":common-impl"))
    implementation(project(":common-packetevents"))
    compileOnly("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")
    compileOnly(libs.packetEvents.spigot)
    implementation(libs.nbtApi)
    implementation(libs.fastboard)
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.mockk)
    testImplementation("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.113.1")
}


