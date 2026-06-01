package buildsrc.convention

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/nms/")
    maven {
        url = uri("https://hub.spigotmc.org/nexus/content/groups/public/")
        content {
            includeGroup("org.spigotmc")
            includeGroup("org.bukkit")
        }
    }
    maven("https://libraries.minecraft.net/")
}