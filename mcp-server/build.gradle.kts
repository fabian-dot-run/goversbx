plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common"))
    implementation(libs.mcp.sdk)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("com.goversbx.mcpserver.Main")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("goversbx-mcp-server.jar")
}
