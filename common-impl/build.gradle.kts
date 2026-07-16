plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":common-api"))
    api(libs.slf4jApi)
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.boostedYaml)
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.mockk)
    testFixturesImplementation(kotlin("test"))
}
