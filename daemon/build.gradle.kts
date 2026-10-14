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
    // Only for McpSchemaReflectionFeature, which native-image runs at build time; never loaded on the JVM.
    compileOnly(libs.graalvm.nativeimage)

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
// binary with no JVM dependency. Needs GraalVM (GRAALVM_HOME) and a C toolchain. native-image can't
// cross-compile: the macOS binary is built on a macOS runner (.github/workflows/release.yml).
//
// Black-box contract tests against a daemon *process* (src/binaryTest): no access to daemon classes,
// only HTTP/MCP from outside plus a scriptable fake sbx. The same suite runs as
//   binaryTestJar     - against the fat jar on this JVM (part of `check`, no GraalVM needed)
//   nativeMetadata    - against the jar under GraalVM's tracing agent; its trace (build/native-metadata)
//                       is the reflection/resource metadata of every native build - nativeCompile
//                       depends on it, so the metadata can never lag behind the code or the suite
//   binaryTestNative  - against the native binary, i.e. what releases ship
// Library metadata comes from GraalVM's reachability-metadata repository; McpSchemaReflectionFeature
// registers every MCP schema type; src/main/resources/META-INF/native-image/ only holds the
// hand-written public/** resource glob.
val binaryTest: SourceSet by sourceSets.creating

dependencies {
    "binaryTestImplementation"(libs.junit.jupiter)
    "binaryTestImplementation"(libs.mcp.sdk)
    "binaryTestImplementation"(libs.jackson.databind)
    "binaryTestRuntimeOnly"(libs.junit.platform.launcher)
    "binaryTestRuntimeOnly"(libs.slf4j.simple)
}

val nativeBinary = layout.buildDirectory.file("native/nativeCompile/goversbx-daemon")
val fatJar = tasks.shadowJar.flatMap { it.archiveFile }
val agentMetadataDir = layout.buildDirectory.dir("native-metadata")
val publicDir = layout.projectDirectory.dir("src/main/resources/public")
val fixturesDir = layout.projectDirectory.dir("src/binaryTest/resources/client-fixtures")

fun registerBinaryTest(name: String, description: String, command: () -> List<String>, configure: Test.() -> Unit = {}) =
    tasks.register<Test>(name) {
        group = "verification"
        this.description = description
        testClassesDirs = binaryTest.output.classesDirs
        classpath = binaryTest.runtimeClasspath
        useJUnitPlatform()
        inputs.dir(publicDir)
        inputs.dir(fixturesDir)
        systemProperty("goversbx.publicDir", publicDir.asFile.absolutePath)
        systemProperty("goversbx.fixturesDir", fixturesDir.asFile.absolutePath)
        // A hung request fails its test instead of stalling the whole run.
        systemProperty("junit.jupiter.execution.timeout.default", "120 s")
        // The command is only known once the binary/jar exists; inputs make Gradle rerun on a rebuild.
        doFirst { systemProperty("goversbx.daemon.command", command().joinToString("\n")) }
        testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        configure()
    }

fun graalvmHome(): String = providers.environmentVariable("GRAALVM_HOME").orNull
    ?: throw GradleException("set GRAALVM_HOME to a GraalVM installation")

val binaryTestJar = registerBinaryTest("binaryTestJar", "Contract tests against the daemon fat jar.",
    { listOf("${System.getProperty("java.home")}/bin/java", "-jar", fatJar.get().asFile.absolutePath) }) {
    dependsOn(tasks.shadowJar)
    inputs.file(fatJar)
}

val nativeMetadata = registerBinaryTest("nativeMetadata",
    "Traces the contract tests against the jar with GraalVM's agent into build/native-metadata (needs GRAALVM_HOME).",
    {
        listOf("${graalvmHome()}/bin/java",
            "-agentlib:native-image-agent=config-merge-dir=${agentMetadataDir.get().asFile.absolutePath}",
            "-jar", fatJar.get().asFile.absolutePath)
    }) {
    dependsOn(tasks.shadowJar)
    inputs.file(fatJar)
    inputs.property("graalvmHome", providers.environmentVariable("GRAALVM_HOME").orElse(""))
    outputs.dir(agentMetadataDir)
    // Each test class starts its own daemon; config-merge-dir merges all of their traces.
    doFirst { delete(agentMetadataDir) }
}

registerBinaryTest("binaryTestNative", "Contract tests against the native binary (needs GRAALVM_HOME).",
    { listOf(nativeBinary.get().asFile.absolutePath) }) {
    dependsOn("nativeCompile")
    inputs.file(nativeBinary)
}

tasks.named("check") { dependsOn(binaryTestJar) }

graalvmNative {
    toolchainDetection.set(false)
    metadataRepository {
        enabled.set(true)
    }
    binaries {
        named("main") {
            imageName.set("goversbx-daemon")
            mainClass.set("run.fabian.daemon.Main")
            buildArgs.addAll(
                "-H:+ReportExceptionStackTraces",
                "--features=run.fabian.daemon.nativeimage.McpSchemaReflectionFeature",
            )
            configurationFileDirectories.from(agentMetadataDir)
        }
    }
}

tasks.named("nativeCompile") { dependsOn(nativeMetadata) }
