plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.repos")
}

dependencies {
    api(project(":common:messaging:api"))
    implementation("org.redisson:redisson:4.6.1")
}