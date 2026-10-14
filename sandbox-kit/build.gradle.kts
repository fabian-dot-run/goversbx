// Not a compiled Java module - just packages the sandbox-customization assets (skill,
// durable-startup draft) into one distributable zip, ready to hand to whatever the real
// host-side sbx kit mechanism turns out to be. No jar/launcher needed anymore - the MCP
// server lives in the daemon now and sandboxes reach it over HTTP, like mcp-gateway.

plugins {
    base
}

val packageKit = tasks.register<Zip>("packageKit") {
    archiveBaseName.set("goversbx-sandbox-kit")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

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
