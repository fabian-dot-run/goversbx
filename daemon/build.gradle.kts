plugins {
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.graalvm.native)
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.javalin)
    implementation(libs.mcp.sdk)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.javalin.testtools)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("run.fabian.daemon.Main")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("goversbx-daemon.jar")
}

// `./gradlew :daemon:nativeCompile` -> build/native/nativeCompile/goversbx-daemon, a standalone
// binary with no JVM dependency. Needs GraalVM (GRAALVM_HOME, or JAVA_HOME pointing at it).
// native-image cannot cross-compile: the macOS binary is built on a macOS runner (.github/workflows).
// Reflection/resource metadata the library metadata repository doesn't cover lives in
// src/main/resources/META-INF/native-image/ - regenerate with `scripts/native-metadata.sh`.
graalvmNative {
    toolchainDetection.set(false)
    metadataRepository {
        enabled.set(true)
    }
    binaries {
        named("main") {
            imageName.set("goversbx-daemon")
            mainClass.set("run.fabian.daemon.Main")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}
