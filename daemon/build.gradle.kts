plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common"))
    implementation(libs.javalin)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("run.fabian.daemon.Main")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("goversbx-daemon.jar")
}
