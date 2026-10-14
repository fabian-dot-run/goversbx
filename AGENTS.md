# AGENTS.md

Guidance for AI coding agents working in this repository. **Start with `docs/status.md`** - the
current implementation state, what is and isn't verified, the next step, and open questions. See
`README.md` for what the project does and `docs/plan.md` for the full design rationale and history
(including the 2026-10-14 revisions that merged the MCP server into the daemon and added reactive,
verified sandbox identity via `sbx secret set-custom` + `sbx ls`).

## Build & run

```bash
./gradlew build                      # build everything, run the full test suite
./gradlew :daemon:test               # unit + fast integration tests only
./gradlew :daemon:shadowJar          # build the daemon fat jar
./gradlew :sandbox-kit:packageKit    # build the sandbox customization zip
scripts/e2e-claude.sh                # end-to-end test on a real sbx HOST (not from inside a sandbox)
```

`scripts/e2e-claude.sh` takes `SBX=<binary>`; to check changes to the script from inside a sandbox,
run it against the stub `scripts/testing/fake-sbx` (usage in its header). Keep `e2e-claude.sh` bash
3.2 compatible - the host is macOS (no `timeout`, no bash 4 features).

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
- **`sandboxidentity`** - registers a sandbox's proxy-verified identity secret, reactively
  (`sbx secret set-custom` + `sbx ls`, see "Sandbox identity" below). No domain - it's a thin
  `application` (+ `port`) → `adapter` wrapper around two `sbx` CLI calls, same shape as
  `networkaccess`'s `sbx` adapter. Publishes `sandboxidentity.api.SandboxIdentityApi`, because
  `mcp` *does* call into this context now (to trigger registration when it sees an unregistered
  sandbox) - same cross-context rule as `networkaccess.api`.

**The rule**: a bounded context must never import another one's `domain`, `.application`, or
`.adapter` directly - only its published `.api` package. This is enforced by convention/code
review, not by the Java module system (JPMS would be real overkill here given Jetty/Reactor aren't
cleanly modularized). If you add a fourth concern to this daemon, give it the same shape.

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

This header is **proxy-verified, not self-reported**, via a confirmed, public `sbx` feature:
`sbx secret set-custom --sandbox <name> --host <host> --env <VAR> --placeholder <p> --value <v>`
makes the sbx proxy rewrite `<p>` to `<v>` in every outbound HTTP header sent to `<host>` -
confirmed to work for arbitrary custom headers (not just `Authorization`), confirmed scopable
per-sandbox, and - this is the important part - **confirmed to apply to already-running sandboxes
without a restart** (checked directly against `docker/sbx-releases`' GitHub release notes via
`gh api`, not a search summary). That last point is why registration can be fully reactive:

- Every agent's startup script (`sandbox-kit/agents/<agent>/startup.sh`) sends the same
  **hardcoded literal** (`goversbx-sandbox-identity-placeholder-v1`) as the header value, never
  read from an env var. `claude mcp add --header` resolves its value once, at registration time,
  and freezes it into `~/.claude.json` - a per-sandbox env var that didn't exist yet at that moment
  would be frozen in as empty forever. A shared hardcoded literal has no such timing dependency:
  the daemon-side substitution rule can start applying at any later point, live. For pi, the
  literal must additionally never contain `${` or start with `!` - pi interpolates env vars and
  runs commands in MCP header values.
- `McpContext.SANDBOX_NAME_HEADER`'s value arriving **unchanged** (still equal to the literal
  placeholder) is the signal that a sandbox isn't registered yet -
  `SandboxIdentityApi.isUnsubstitutedPlaceholder(...)`. `RequestNetworkAccessTool` checks this
  before doing anything else; if true, it calls `registerAnyUnregisteredSandboxes()` (which runs
  `sbx ls` and registers every running sandbox this daemon process doesn't already know about -
  not just the one that triggered it) and tells the agent to retry, without creating a
  `NetworkAccessRequest`.
- `GOVERSBX_SBX_LS_FORMAT_ARGS` (default `--format json`) exists because `sbx ls`'s JSON flag
  spelling changed across `sbx` versions (`--json` before v0.48.0) - don't hardcode one spelling if
  you touch `SbxCliSandboxListAdapter`.
- `SandboxSummary` (the `sbx ls` JSON row DTO) only declares `name`/`status` and ignores the rest
  via `@JsonIgnoreProperties(ignoreUnknown = true)` - this is deliberate, not an oversight; don't
  "complete" it with every field sbx happens to return.

The admin-only `POST /api/sandboxes/{name}/register` endpoint still exists for pre-warming a
sandbox manually, but nothing requires it anymore - don't assume it's the only path a sandbox gets
registered through.

- The flag is `--host`, not `--target` (`--target` only exists in an old example, docker/sbx-releases#7;
  the first real e2e run failed on it). sbx flag names have churned - prefer a real invocation from
  an issue or `sbx <cmd> --help` output over doc summaries.
- `registerAnyUnregisteredSandboxes()` registers every running sandbox matching
  `GOVERSBX_IDENTITY_SANDBOX_PATTERN` (default `.*`, i.e. all of them - including sandboxes that have
  nothing to do with goversbx). Narrow it for test runs.
- sbx keys custom secrets by placeholder (docker/sbx-releases#230) and we use one placeholder for
  every sandbox. That per-sandbox `--sandbox` scoping keeps them apart is exactly what step 4b of
  `scripts/e2e-claude.sh` checks against a real host - see `docs/status.md` for the fallback if not.

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

## Sandbox kit

Layout: one shared skill (`sandbox-kit/skill/goversbx-network-access/SKILL.md`) plus one
directory per agent under `sandbox-kit/agents/<agent>/` (`startup.sh` + `manifest-fragment.json`).
The daemon is agent-agnostic - anything that speaks MCP over Streamable HTTP and sends the
placeholder header works - so adding an agent means adding a directory here, not touching
`daemon/`. `packageKit` bundles `skill/` and `agents/` and marks every `*.sh` executable.

- `agents/claude/` registers via `claude mcp add --transport http --header ...`.
- `agents/pi/` (pi >= 1.0, MCP in core) merges a `goversbx-network` entry into
  `~/.pi/agent/mcp.json` with Node, keeping other entries and failing on a malformed file rather
  than overwriting it. It uses the user-level file because pi only reads a project's `.pi/mcp.json`
  after the project is trusted, and `"exposure": "direct"` because pi's default (`codemode`)
  hides MCP tools from the model's tool list. It also copies the skill into `~/.agents/skills/`.
  The JSON is edited directly instead of calling `pi mcp add`, so the script doesn't need pi on
  `PATH` or depend on its CLI flags; with pi 1.2.0, `pi mcp add --header ... --exposure direct`
  writes a byte-identical entry.
- `agents/omp/` (oh-my-pi) merges a `"type": "http"` entry into `<agentDir>/mcp.json` and copies
  the skill into `<agentDir>/skills/`. `<agentDir>` is profile-scoped in omp: `~/.omp/agent` by
  default, `~/.omp/profiles/<name>/agent` under `OMP_PROFILE`/`PI_PROFILE` (omp then ignores the
  default profile's files), `PI_CODING_AGENT_DIR` overrides the default; `OMP_AGENT_DIR` forces a
  path. omp's `~/.agents/skills` provider is opt-in for user skills, hence the native skills dir.
  omp runs on Bun, so the merge uses `node` if present and falls back to `bun`.
- The skill names tools without assuming a prefix: pi exposes them as
  `mcp__goversbx-network__<tool>`, omp as `mcp__goversbx_network_<tool>`.
- Header values are interpreted by some agents: pi and omp expand `${VAR}` and run `!command`,
  and omp also substitutes a value that is exactly the name of a set env var. Keep the placeholder
  a plain literal that is none of these.

Everything under `agents/` is an explicitly-marked **draft**: it mirrors the in-sandbox
*consumption* shape observed on a running `sbx` sandbox (durable-startup command format, the
agents' own MCP config formats), but the host-side mechanism for actually registering a new kit
with `sbx` has not been confirmed. Don't remove the "DRAFT" markers or treat these files as
settled without checking against real `sbx` docs first (see `docs/plan.md`, open question 2 /
milestone M4). The pi and omp scripts' config merging is tested against fresh, existing, and
malformed configs (plus named profiles and a missing JS runtime for omp). The pi script is also
verified against real pi 1.2.0 (`pi mcp list` connects and lists both tools as `direct`, the
placeholder header is sent verbatim, pi's resource loader discovers the skill); the omp script has
not been run against a real omp installation. Note that pi's exported `loadSkills()` does not scan
`~/.agents/skills` - use `DefaultResourceLoader` if you ever re-check skill discovery that way.
