rootProject.name = "EasyLib"

gradle.beforeProject {
    group = "com.github.mayblock"
    version = property("version") as String
}

include(":common-api")
include(":common-impl")
include(":common-packetevents")
include(":platform-bukkit-api")
include(":platform-bukkit-impl")