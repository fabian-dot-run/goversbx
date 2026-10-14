// Not a compiled Java module - just packages the sandbox-customization assets (the shared skill
// plus one durable-startup draft per agent under agents/<name>/) into one distributable zip, ready
// to hand to whatever the real host-side sbx kit mechanism turns out to be. No jar/launcher - the
// MCP server lives in the daemon and sandboxes reach it over HTTP, like mcp-gateway.

plugins {
    base
}

val packageKit = tasks.register<Zip>("packageKit") {
    archiveBaseName.set("goversbx-sandbox-kit")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from("skill") {
        into("skill")
    }
    from("agents") {
        into("agents")
        filesMatching("**/*.sh") {
            permissions {
                unix("755")
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn(packageKit)
}
