# AGENTS.md

Guidance for AI coding agents working in this repository. See `README.md` for what the project
does and `docs/plan.md` for the full design rationale, open questions, and milestone status
(including the 2026-10-14 revisions that merged the MCP server into the daemon and added verified
sandbox identity via `sbx secret set-custom`).

## Build & run

```bash
./gradlew build                      # build everything, run the full test suite
./gradlew :daemon:test               # unit + fast integration tests only
./gradlew :daemon:shadowJar          # build the daemon fat jar
./gradlew :sandbox-kit:packageKit    # build the sandbox customization zip
```

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
- `junit-jupiter` alone is not enough for Gradle 9's test task to find JUnit 5 tests - it also
  needs `org.junit.platform:junit-platform-launcher` on `testRuntimeOnly`
  (`daemon/build.gradle.kts`). Without it, `./gradlew :daemon:test` fails with "Could not load
  JUnit Platform" rather than just finding zero tests, which is a confusing failure mode if you
  ever strip this dependency while "cleaning up" the build file.

## Bounded contexts and hexagonal layering (`daemon` module)

The daemon is the **only** deployable now (there used to be a separate `mcp-server` process and a
shared `common` module - both were folded in here; see `docs/plan.md`'s 2026-10-14 revision for
why). Internally it is split into bounded contexts under `daemon/src/main/java/run/fabian/daemon/`:

- **`networkaccess`** - the approval workflow. Layered `domain` → `application` (+ `port`) →
  `adapter`, with a published `api` package (`NetworkAccessApi`, `NetworkAccessRequestView`,
  `RequestStatus`).
- **`mcp`** - pure MCP-protocol translation. Has no domain of its own by design - it only adapts
  MCP tool calls into calls on `networkaccess.api`.
- **`sandboxidentity`** - registers a sandbox's proxy-verified identity secret before that sandbox
  starts (`sbx secret set-custom`, see "Sandbox identity" below). No domain either - it's a thin
  `application` (+ `port`) → `adapter` wrapper around one `sbx` CLI call, same shape as
  `networkaccess`'s `sbx` adapter. `mcp` doesn't call into this context at all - it just reads the
  header this context's registration makes trustworthy.

**The rule**: a bounded context must never import another one's `domain`, `.application`, or
`.adapter` directly - only its published `.api` package (where one exists; `sandboxidentity` has
none because nothing else needs to call it). This is enforced by convention/code review, not by the
Java module system (JPMS would be real overkill here given Jetty/Reactor aren't cleanly
modularized). If you add a fourth concern to this daemon, give it the same shape.

Within `networkaccess`: `domain` has zero framework dependencies (no Javalin, no Jetty, no MCP
SDK, no `ProcessBuilder`) and owns its own invariants - e.g. `NetworkAccessRequest.approve()`/
`.deny()` throw `AlreadyDecidedException` themselves rather than callers re-checking status first.
`application` (`NetworkAccessService`) orchestrates the domain through driven ports
(`RequestRepository`, `SbxPolicyPort`) it doesn't know the implementation of. `adapter` implements
those ports (`adapter.persistence`, `adapter.sbx`) and hosts the driving HTTP adapter
(`adapter.web.AdminApiController`).

- **Two separate Javalin instances in one process, on purpose** (`Main.java`): one hosts only the
  mounted MCP servlet + `/api/health` on the sandbox-facing port (`GOVERSBX_SANDBOX_API_PORT`,
  default 8787); the other hosts the admin REST API + static web UI, bound to `127.0.0.1` only
  (`GOVERSBX_ADMIN_PORT`, default 8788). This is a security boundary, not an accident: there's no
  authn in this MVP, and a sandbox must never be able to reach the approve/deny endpoints. If you
  add a new endpoint, think about which of the two it belongs on before adding it, and don't merge
  them into one listener to "simplify" it.
- `SbxCliPolicyAdapter` and `SbxCliCustomSecretAdapter` both default to `GOVERSBX_SBX_DRY_RUN=true`
  (log the command they'd run instead of executing it). Don't flip this default - real sandboxes
  get their network policy and identity secret changed by these calls.

### Sandbox identity

Identity travels as an HTTP header (`X-Goversbx-Sandbox-Name`, see `McpContext`'s
`contextExtractor`), read by the mcp tool handlers via
`exchange.transportContext().get(McpContext.SANDBOX_NAME_CONTEXT_KEY)` - there is no more
per-process `$SANDBOX_NAME` env var now that one daemon process serves every sandbox.

This header is now **proxy-verified, not self-reported**, via a confirmed, public `sbx` feature:
`sbx secret set-custom --sandbox <name> --target <host> --env <VAR> --placeholder <p> --value <v>`
makes a sandbox see only the placeholder `<p>` (as env var `<VAR>`), while the sbx proxy rewrites
`<p>` to `<v>` in every outbound HTTP header sent to `<host>` - confirmed to work for arbitrary
custom headers (not just `Authorization`), confirmed scopable per-sandbox. `sandboxidentity`'s
`SandboxIdentityService.register(sandboxName)` runs this with `<v> = sandboxName` itself (identity
needs to be unforgeable, not secret, so there's no separate opaque token to manage).
`sandbox-kit/startup/000-cmd.sh` reads the placeholder from `$GOVERSBX_SANDBOX_TOKEN` (not
`$SANDBOX_NAME`) when building the header.

**This only works if an operator calls `POST /api/sandboxes/<name>/register` (loopback admin API)
before that sandbox's kit-install step runs** - `$GOVERSBX_SANDBOX_TOKEN` doesn't exist until then.
That ordering isn't automated yet (see `docs/plan.md`'s second 2026-10-14 revision) - don't assume
every sandbox has gone through this, and don't remove the `McpContext` fallback to
`"unknown-sandbox"` for the case where it hasn't.

## Testing conventions

- Unit tests (`networkaccess/domain`, `networkaccess/application`): plain JUnit 5, no Javalin/Jetty
  involved. Use the real `InMemoryRequestRepository` as a fast, correct test double rather than
  mocking the repository port.
- Fast integration tests (`networkaccess/adapter/web`, `mcp`, `sandboxidentity/adapter/web`): use `io.javalin.testtools
  .JavalinTest.test(app, (server, client) -> {...})` - boots the real app on an ephemeral port,
  tears down automatically. For the MCP endpoint specifically, drive it with the MCP Java SDK's
  own sync client (`McpClient.sync(HttpClientStreamableHttpTransport.builder(origin + "/mcp")
  .build()).build()`) against `client.getOrigin()` rather than hand-rolling JSON-RPC - see
  `McpEndpointIntegrationTest` for the pattern, including how to exercise "a human approves while
  the tool call is blocked" by calling `NetworkAccessService` directly from a background thread in
  the same JVM.
- The `javalin-testtools` artifact version must track the `javalin` version exactly
  (`gradle/libs.versions.toml`) - they're versioned in lockstep by upstream.

## MCP Java SDK notes

Pinned at `2.0.1` (`gradle/libs.versions.toml`, `mcp-sdk`). This SDK is young and its API has
churned across versions; verify against the actual jar with `javap` rather than assuming method
names from memory if you bump it (`mcp-core-<version>.jar` can be pulled straight from Maven
Central).

- **Transport: HTTP (Streamable), not stdio.** `McpContext.build(...)` constructs an
  `HttpServletStreamableServerTransportProvider` (it literally **is** a `jakarta.servlet
  .http.HttpServlet`) and mounts it onto the sandbox-facing Javalin instance's own Jetty server via
  `config.jetty.modifyServletContextHandler(handler -> handler.addServlet(new
  ServletHolder(mcp.servlet()), "/mcp/*"))` - no second web server, no Spring. This mirrors how the
  platform's own `mcp-gateway` is registered (`claude mcp add ... --transport http`), which is the
  whole reason this was moved out of a separate stdio process in the first place (see
  `docs/plan.md`'s 2026-10-14 revision).
- The artifact `io.modelcontextprotocol.sdk:mcp` is just a thin aggregator POM pulling in
  `mcp-core` (the actual classes) and `mcp-json-jackson3` (the default JSON mapper
  implementation, using `tools.jackson.*` — Jackson 3, **not** the classic `com.fasterxml.jackson`
  used by `networkaccess.adapter.web.JsonSupport`; these are two unrelated Jackson majors living in
  the same classpath on purpose, don't try to unify them).
- There is no SDK-wide "default JSON mapper" static accessor. `McpContext` constructs one
  explicitly via `new JacksonMcpJsonMapperSupplier().get()`.
- A tool handler reads per-request context (like the sandbox-name header) via
  `McpSyncServerExchange.transportContext()`, populated by the `McpTransportContextExtractor`
  passed to the transport provider's builder - this is how request-scoped data gets into a tool
  call now that there's no per-process env var to read.

## Sandbox-kit caveat

Everything under `sandbox-kit/startup/` is an explicitly-marked **draft**: it mirrors the
in-sandbox *consumption* shape observed on a running `sbx` sandbox (durable-startup command
format, `claude mcp add --transport http` registration, `~/.claude/skills/synced/` layout), but
the host-side mechanism for actually registering a new kit/skill bucket with `sbx` has not been
confirmed. Don't remove the "DRAFT" markers or treat these files as settled without checking
against real `sbx` docs/source first (see `docs/plan.md`, open question 2 / milestone M4). There is
no jar or launcher script to package anymore - `sandbox-kit/build.gradle.kts`'s `packageKit` task
only bundles `skill/` and `startup/`.
