plugins {
    application
    alias(libs.plugins.shadow)
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
