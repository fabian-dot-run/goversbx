// Not a compiled Java module - just packages the sandbox-customization assets
// (mcp-server jar, launcher script, skill, durable-startup draft) into one distributable
// zip, ready to hand to whatever the real host-side sbx kit mechanism turns out to be.

plugins {
    base
}

val packageKit = tasks.register<Zip>("packageKit") {
    archiveBaseName.set("goversbx-sandbox-kit")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    dependsOn(":mcp-server:shadowJar")

    from(project(":mcp-server").layout.buildDirectory.file("libs/goversbx-mcp-server.jar")) {
        into("bin")
    }
    from("launcher") {
        into("bin")
        filePermissions {
            unix("755")
        }
    }
    from("skill") {
        into("skill")
    }
    from("startup") {
        into("startup")
    }
}

tasks.named("assemble") {
    dependsOn(packageKit)
}
