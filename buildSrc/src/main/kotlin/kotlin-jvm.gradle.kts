// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")
    // Apply Maven Publish plugin to enable publishing the library.
    `maven-publish`
}

kotlin {
    // Use a specific Java version to make it easier to work in different environments.
    jvmToolchain(25)
}

// Single source of truth for this module's published coordinate name,
// reused by both the maven artifactId and the jar archive base name.
val coordinateName = "${rootProject.name}-${project.name}"

val rootExtra = rootProject.extra
val gitBranch = rootExtra["gitBranch"] as String
val gitCommitHash = rootExtra["gitCommitHash"] as String

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set(coordinateName)
    archiveVersion.set(project.version.toString())
    manifest {
        attributes(
            "Implementation-Title" to coordinateName,
            "Implementation-Version" to project.version.toString(),
            "Build-Branch" to gitBranch,
            "Build-Commit" to gitCommitHash,
        )
    }
}

tasks.publish {
    dependsOn(tasks.check)
}
tasks.publishToMavenLocal {
    dependsOn(tasks.check)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group as String
            artifactId = coordinateName
            version = project.version as String
        }
    }
}
