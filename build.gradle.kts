plugins {
    java
}

allprojects {
    group = "run.fabian"
    version = "0.3.0"

    repositories {
        mavenCentral()
    }
}

tasks.withType<org.gradle.api.tasks.wrapper.Wrapper> {
    // GitHub's release-asset redirect chain 401s on HEAD requests (but works fine for GET),
    // which breaks Gradle's own distribution-url preflight check through some proxies.
    validateDistributionUrl.set(false)
}

configure(subprojects.filter { it.name != "sandbox-kit" }) {
    apply(plugin = "java")

    tasks.withType<JavaCompile> {
        options.release.set(21)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
