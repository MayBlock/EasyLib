plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":common-api"))
    api(libs.kotlinReflect)
    api(libs.bundles.kotlinxEcosystem)
    api(libs.slf4jApi)
    implementation(libs.boostedYaml)
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.mockk)
    testFixturesImplementation(kotlin("test"))
}
