# AGENTS.md

Guidance for AI coding agents working in this repository. See `README.md` for what the project
does and `docs/plan.md` for the full design rationale, open questions, and milestone status.

## Build & run

```bash
./gradlew build -x test              # build everything
./gradlew :daemon:shadowJar          # build the daemon fat jar
./gradlew :mcp-server:shadowJar      # build the mcp-server fat jar
./gradlew :sandbox-kit:packageKit    # build the sandbox customization zip
```

There are no JUnit tests yet — verification so far has been manual (curl against the daemon's
REST API, and a Python-driven MCP JSON-RPC stdio session against the mcp-server jar; see
`docs/plan.md` "Verification" / "Status"). If you add real behavior, prefer adding a JUnit 5 test
under the relevant module's `src/test/java` over another manual script.

### JDK/Gradle quirks (don't "fix" these without reason)

- The root `build.gradle.kts` does **not** use a Gradle Java toolchain. Instead every
  `JavaCompile` task gets `options.release.set(21)`. This was deliberate: only a single (newer)
  JDK is guaranteed to be on `PATH` in the environments this builds in, and toolchain
  auto-provisioning needs network access to a JDK distribution service that may not be allowed.
  `--release 21` compiles Java-21-compatible bytecode with whatever JDK is actually present.
- `gradle/wrapper/gradle-wrapper.properties` has `validateDistributionUrl=false`. Gradle's own
  wrapper-generation preflight does a HEAD request against the distribution URL, and GitHub's
  release-asset redirect chain (services.gradle.org → github.com → objects.githubusercontent.com)
  401s/403s on HEAD even though GET works fine. Don't re-enable this unless you've confirmed HEAD
  works through whatever network path you're building on.
- The Gradle version is pinned to a specific 9.x release in the wrapper properties because older
  Gradle (8.11 and earlier tested) fails to even configure a Kotlin DSL build script on newer JDKs
  — its embedded Kotlin compiler can't parse a JDK version string like `25.0.4.1`. If you bump the
  JDK running Gradle, check this still works before assuming it's fine.

## Module conventions

- `common` has **no I/O dependencies** beyond Jackson for JSON. It's shared by both the sandbox
  side (`mcp-server`) and the host side (`daemon`) — don't add anything here that only makes sense
  on one side (e.g. Javalin, `ProcessBuilder` calls, the MCP SDK).
- `daemon` runs **two separate Javalin instances** in one process on purpose
  (`SandboxApiServer` on 8787, `AdminServer` on 127.0.0.1:8788). This is a security boundary, not
  an accident: the sandbox-facing port must never expose approve/deny, because there's no authn in
  this MVP and a sandbox could otherwise self-approve its own requests. If you add a new endpoint,
  think about which of the two servers it belongs on before adding it, and don't merge them into
  one listener to "simplify" it.
- `mcp-server` talks to the MCP SDK over **stdio**. `System.out` is reserved for JSON-RPC frames —
  never write anything to stdout; use the SLF4J logger (goes to stderr) for all diagnostics. This
  will silently corrupt the protocol stream if violated, not throw an obvious exception.
- `SbxPolicyClient` defaults to `GOVERSBX_SBX_DRY_RUN=true` (logs the command it would run instead
  of executing it). Don't flip this default — real sandboxes get their network policy changed by
  this call, and flipping the default would make every local/dev run of the daemon start mutating
  real `sbx` policy.

## MCP Java SDK notes

Pinned at `2.0.1` (`gradle/libs.versions.toml`, `mcp-sdk`). This SDK is young and its API has
churned across versions. If you bump it:
- The artifact `io.modelcontextprotocol.sdk:mcp` is just a thin aggregator POM pulling in
  `mcp-core` (the actual classes) and `mcp-json-jackson3` (the default JSON mapper
  implementation, using `tools.jackson.*` — Jackson 3, **not** the classic `com.fasterxml.jackson`
  used by `common`/`daemon`/the mcp-server's own `DaemonClient`; these are two unrelated Jackson
  majors living in the same classpath on purpose, don't try to unify them).
- There is no SDK-wide "default JSON mapper" static accessor. `Main.java` constructs one
  explicitly via `new JacksonMcpJsonMapperSupplier().get()`. If a future SDK version adds a real
  default accessor, feel free to simplify to that — but verify it first with `javap` against the
  actual jar rather than assuming method names from memory; this SDK's class/builder names moved
  around enough between versions that guessing is a bad bet. (`mcp-core-<version>.jar` can be
  pulled straight from Maven Central and inspected with `javap -classpath <jar> <class>`.)
- `StdioServerTransportProvider` requires an `McpJsonMapper` in its constructor — it is not
  injected later by `McpServer.sync(...)`'s builder.

## Sandbox-kit caveat

Everything under `sandbox-kit/startup/` is an explicitly-marked **draft**: it mirrors the
in-sandbox *consumption* shape observed on a running `sbx` sandbox (durable-startup command
format, `claude mcp add` registration, `~/.claude/skills/synced/` layout), but the host-side
mechanism for actually registering a new kit/skill bucket with `sbx` has not been confirmed. Don't
remove the "DRAFT" markers or treat these files as settled without checking against real `sbx`
docs/source first (see `docs/plan.md`, open question 2 / milestone M4).
