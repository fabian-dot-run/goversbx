plugins {
    // Lets Gradle download a JDK it cannot find locally - used for GraalVM by the native build
    // (daemon/build.gradle.kts), so `./gradlew :daemon:binaryTestNative` works on a fresh machine.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "goversbx"

include("daemon")
include("sandbox-kit")
