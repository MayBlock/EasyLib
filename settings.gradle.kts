rootProject.name = "EasyLib"

gradle.beforeProject {
    // Gradle 用 group:name:version 作为项目在依赖解析中的模块标识，而模块树里存在多个
    // 同名叶子项目（:common:base:api、:common:packetevents:api、:platform:bukkit:api
    // 的 name 同为 "api"，各 impl 亦然）。若它们共享同一 group，冲突解析会把这些项目
    // 视为同一模块并相互替换，使项目依赖到自身（compileKotlin -> 自身 jar），从而报出
    // 实际并不存在的循环依赖。因此这里按父路径为 group 追加后缀以保证唯一。
    //
    // 注意：这是构建内部标识，不影响对外发布坐标 —— convention 插件里的 publishing 块
    // 使用 rootProject.group（即 com.github.mayblock）与路径推导的 artifactId。
    group = "com.github.mayblock" + path.dropLast(name.length + 1).replace(':', '.')
    val base = property("version") as String
    val isSnapshot = (property("snapshot") as String).toBooleanStrict()
    version = base + if (isSnapshot) "-SNAPSHOT" else ""
}

include(":common:base:api")
include(":common:base:impl")
include(":common:packetevents:api")
include(":common:packetevents:impl")
// 后端适配器，不做 api/impl 拆分：它没有面向上游插件的契约，
// 唯一的消费者是 *:impl 模块。见 CLAUDE.md「模块结构与依赖方向」。
include(":common:redis")
include(":common:cache:api")
include(":common:cache:impl")
include(":common:messaging:api")
include(":common:messaging:impl")
include(":platform:bukkit:api")
include(":platform:bukkit:impl")