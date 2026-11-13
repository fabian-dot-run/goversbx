// Not a compiled Java module. kits/goversbx/ is a real sbx v3 mixin kit (goversbx.yaml +
// goversbx.dockerfile + stage/), usable straight from the source tree
// (`sbx create claude --kit sandbox-kit/kits/goversbx ...`, sbx >= 0.51) - sbx builds it on the host.
// Because a kit must be self-contained (its build context is the kit directory), it carries its own
// copy of the shared skill under skill/ - `syncKitSkills` refreshes that copy, and `check` fails
// if it has drifted. `packageKits` zips the kit for distribution.

plugins {
    base
}

val sharedSkill = layout.projectDirectory.dir("skill/goversbx-network-access")

// Where each kit stages the skill, relative to the kit directory. The runtime exposes it in the
// agents' skills directories (agent-skill@1); register-mcp.sh links it where the runtime does not.
val kitSkillDirs = mapOf(
    "goversbx" to "stage/usr/share/goversbx/skills",
)

val syncKitSkills = tasks.register("syncKitSkills") {
    group = "kit"
    description = "Copies skill/goversbx-network-access into every kit's staged payload."
    doLast {
        kitSkillDirs.forEach { (kit, dir) ->
            project.sync {
                from(sharedSkill)
                into(layout.projectDirectory.dir("kits/$kit/$dir/goversbx-network-access"))
            }
        }
    }
}

val verifyKitSkills = tasks.register("verifyKitSkills") {
    group = "verification"
    description = "Fails if a kit's copy of the skill differs from skill/goversbx-network-access."
    inputs.dir(sharedSkill)
    kitSkillDirs.forEach { (kit, dir) -> inputs.dir("kits/$kit/$dir") }
    doLast {
        val source = sharedSkill.asFile
        val expected = source.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(source).path to it.readBytes() }
        kitSkillDirs.forEach { (kit, dir) ->
            val copy = file("kits/$kit/$dir/goversbx-network-access")
            val actual = copy.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(copy).path to it.readBytes() }
            val same = expected.keys == actual.keys &&
                expected.all { (path, bytes) -> bytes.contentEquals(actual.getValue(path)) }
            if (!same) {
                throw GradleException("kits/$kit/$dir/goversbx-network-access is out of date - run ./gradlew :sandbox-kit:syncKitSkills")
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyKitSkills)
}

val packageKits = tasks.register("packageKits") {
    group = "kit"
    description = "Zips every kit under kits/ into build/distributions/<kit>.zip (descriptor at the root)."
    dependsOn(verifyKitSkills)
}

kitSkillDirs.keys.forEach { kit ->
    val zip = tasks.register<Zip>("package-$kit") {
        group = "kit"
        archiveFileName.set("$kit.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        from("kits/$kit")
        filesMatching("**/*.sh") {
            permissions {
                unix("755")
            }
        }
    }
    packageKits.configure { dependsOn(zip) }
}

tasks.named("assemble") {
    dependsOn(packageKits)
}
