rootProject.name = "EasyLib"

gradle.beforeProject {
    group = "com.github.mayblock"
    val base = property("version") as String
    val isSnapshot = (property("snapshot") as String).toBooleanStrict()
    version = base + if (isSnapshot) "-SNAPSHOT" else ""
}

include(":common-api")
include(":common-impl")
include(":common-packetevents")
include(":platform-bukkit-api")
include(":platform-bukkit-impl")