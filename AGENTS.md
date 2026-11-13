# AGENTS.md

Guidance for AI coding agents working in this repository. **Start with `docs/status.md`** - the
current implementation state, what is and isn't verified, the next step, and open questions.
**What goversbx is for, and how to decide what goes in: `docs/vision.md`** (proactive permission
gate for cheap project switching, follow sbx where no feature changes fundamentally, easy entry).
**Features are spec-driven**: a feature starts as a spec under `docs/specs/` (convention in
`docs/specs/README.md`), is accepted by the user, and is implemented against the spec's
Given/When/Then scenarios, each of which names the test level that pins it. See
`README.md` for what the project does and `docs/plan.md` for the full design rationale and history
(including the 2026-10-14 revisions that merged the MCP server into the daemon and added reactive,
verified sandbox identity via `sbx secret set-custom` + `sbx ls`).

## Releasing

`CHANGELOG.md` is the release notes: every user-visible change gets a line in the upcoming version's
section as part of the change itself, not at release time. `scripts/release-notes.sh <version>`
prints a version's section; the release workflow publishes it as the GitHub release body and the
Homebrew formula's caveats link to it. Bump the version in `build.gradle.kts` and
`McpContext`'s server info together (the v3 kit carries no version), then push the `v<version>` tag.

## Build & run

```bash
./gradlew build                      # build everything, run the full test suite
./gradlew :daemon:test               # unit + fast integration tests only
./gradlew :daemon:shadowJar          # build the daemon fat jar
./gradlew :sandbox-kit:packageKits   # zip each sbx kit (also: syncKitSkills after editing the skill)
./gradlew :daemon:binaryTestJar      # contract suite (src/binaryTest) against the jar - part of check
./gradlew :daemon:binaryTestNative   # trace -> native binary -> contract suite (GRAALVM_HOME + C toolchain)
./gradlew :daemon:e2eTest            # e2e suite (src/e2eTest) against a REAL signed-in sbx: real daemon, real sandboxes, real policy changes
scripts/package-release.sh           # everything above + release tarball for this platform
scripts/testing/record-client-fixtures.sh   # re-record real Claude Code/pi/opencode MCP traffic
scripts/goversbx run                 # on the HOST, in a project dir: daemon, user defaults, sbxenv.yaml, sbx env create/run
scripts/e2e-claude.sh                # end-to-end test on a real sbx HOST (not from inside a sandbox)
scripts/diagnose-substitution.sh     # on the HOST: which `set-custom --host` forms the proxy substitutes
```

`./gradlew :daemon:e2eTest` is the e2e suite proper (`GOVERSBX_E2E_*` knobs in `E2eConfig`; runs on the host
or inside the nested dev sandbox, see `docs/status.md`). The bash scripts remain for the pi/omp/opencode kits.
`scripts/e2e-claude.sh` takes `SBX=<binary>`; to check changes to the script from inside a sandbox,
run it against the stub `scripts/testing/fake-sbx` (usage in its header); the same works for
`scripts/goversbx` (`SBX=... GOVERSBX_STATE_DIR=<tmp>`). Keep the host scripts bash
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

### Native image (macOS release via Homebrew)

- Releases ship a GraalVM native binary, not the jar. native-image only sees reflection and
  classpath resources it's told about. Sources of that metadata:
  - **generated on every native build**: `nativeCompile` depends on `nativeMetadata`, which runs
    the contract suite (`src/binaryTest`) against the jar under GraalVM's tracing agent into
    `build/native-metadata/` and feeds that to native-image (`configurationFileDirectories`).
    Nothing generated is committed, so it can't go stale. Don't reintroduce committed agent output.
  - `McpSchemaReflectionFeature` (`--features`): registers every `McpSchema` type, because which
    ones a session deserializes depends on the client (v0.2.0 broke on Claude Code's capabilities).
  - `src/main/resources/META-INF/native-image/run.fabian/goversbx-daemon-static/`: the hand-written
    `public/**` glob.
  - GraalVM's reachability-metadata repository for libraries (`metadataRepository`).
- **The suite is the metadata.** A code path no test exercises has no metadata, and that fails
  only at runtime in the binary - the jar and `./gradlew check` stay green. So any new route, JSON
  type, sbx interaction or error path needs a test in `src/binaryTest`, and `binaryTestNative` must
  pass before a release (`scripts/package-release.sh` and the release workflow run it).
- `src/binaryTest` is black-box on purpose: it must not depend on daemon classes (no
  `main` output on its classpath), only HTTP/MCP plus `FakeSbx`. Error paths count: v0.2.0's
  binary also answered every MCP error with a 500, because Jackson serializing an exception needs
  `Throwable` reflection - found by the suite, not by hand.
- native-image can't cross-compile: the macOS arm64 binary is built on an Apple Silicon runner
  (`.github/workflows/release.yml`). A Linux build in a sandbox is still a valid check of the
  metadata. `--no-fallback` is gone on purpose (deprecated no-op in GraalVM 25).

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
  `networkaccess`'s `sbx` adapter, plus a `SandboxRegistrationStore` port whose
  `adapter.persistence.FileSandboxRegistrationStore` keeps registrations across daemon restarts
  (`~/.local/state/goversbx/sandbox-registrations.json`, see "Sandbox identity" below). Publishes
  `sandboxidentity.api.SandboxIdentityApi`, because
  `mcp` *does* call into this context now (to trigger registration when it sees an unregistered
  sandbox) - same cross-context rule as `networkaccess.api`.

- **`userdefaults`** - not a daemon concern at all: the file work behind `goversbx setup/init/run`
  (spec 0003 slice 3), run as `goversbx-daemon config ...` and exiting before any listener starts
  (`Main` dispatches on `args[0]`). It lives in the binary because the launcher is bash and the
  work needs a YAML parser. `UserDefaultsEditor` is pure text in/out: it owns one `kits` entry of
  `~/.sbxenv.yaml` (path ending in `/kits/goversbx`) and the `agent` when there is none.
  **It never re-serializes the user's file**: snakeyaml-engine is used only to find positions
  (`Compose`, node marks - code points, converted to char offsets), every change is a splice into
  the original text, a layout it cannot splice (flow-style entry that needs an arg, `kits` not a
  list, invalid YAML) is refused with `UnusableFileException`, and every result is planned again
  and must come out clean before it is written. Don't replace this with a load/dump round trip -
  it would reformat comments and quoting the user wrote. `ConfigCommand` does argv, files (backup,
  temp + rename, symlink target, mode) and exit codes (0 ok / 1 unusable / 2 drift on `--check` /
  3 agent needed - nothing is written with 3). The home directory is `$HOME` first, like sbx's Go
  `os.UserHomeDir`, so both mean the same file (and tests can redirect it).

**The rule**: a bounded context must never import another one's `domain`, `.application`, or
`.adapter` directly - only its published `.api` package. This is enforced by convention/code
review, not by the Java module system (JPMS would be real overkill here given Jetty/Reactor aren't
cleanly modularized). If you add a fourth concern to this daemon, give it the same shape.

`networkaccess`'s aggregate is one request for exactly one host (`NetworkAccessRequest.getHost()`),
always pending until a human decides it - the service keeps no memory of earlier grants, so a
request for a host approved before is asked again. The F3 admin-port guard is
`domain.LocalTargetGuard`, applied by the service in `hostRejectionReason`/`createRequest` (the
admin port is a constructor argument); the MCP tool has no guard of its own.

Within `networkaccess`: `domain` has zero framework dependencies (no Javalin, no Jetty, no MCP
SDK, no `ProcessBuilder`) and owns its own invariants - e.g. `NetworkAccessRequest.approve()`/
`.deny()` throw `AlreadyDecidedException` themselves rather than callers re-checking status first.
Host input validation lives here too: `HostValidation` is the single rule for what a requestable host
is (threat-model F2 - a plain hostname, no wildcards/URLs/IP literals), enforced as an invariant in
`createRequest` (`InvalidHostException`) and exposed to the `mcp` tool via
`NetworkAccessApi.hostRejectionReason` so that context never imports the domain directly.
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
  them into one listener to "simplify" it. The `127.0.0.1` bind is necessary but **not** sufficient on
  its own: the proxy re-originates sandbox traffic as host loopback, so a sandbox CAN reach the admin
  port once any loopback host is allowed (threat-model F3, confirmed via
  `scripts/diagnose-admin-reachability.sh`). So the admin API is **token-gated**
  (`admin.AdminTokenFilter`, wired first in `Main`): every `/api/*` call except `/api/health` needs
  the token (`Authorization: Bearer` or `?token=`); static files and health stay public. The token
  lives in `admin.DaemonConfig` (file `~/.local/state/goversbx/admin-token`, or `GOVERSBX_ADMIN_TOKEN`;
  the same state dir holds `sandbox-registrations.json`, see "Sandbox identity"),
  reaches the browser via `goversbx dashboard`, and a sandbox never has it. A new admin endpoint must
  stay under `/api/*` so it's covered; don't add public admin endpoints. `RequestNetworkAccessTool`
  additionally refuses a request that targets the daemon's own admin port or a `*` wildcard
  (`McpContext.build` is passed `selfAdminPort`) - keep that too.
- The `admin` package holds daemon-wide infrastructure wired by `Main` (not a bounded context):
  `DaemonConfig` (admin token + the user-editable `~/.config/goversbx/config.properties`, env overrides
  via `GOVERSBX_*`), `AdminTokenFilter`, `BrowserOpener`, and `DashboardAutoOpener`. The auto-opener
  opens the dashboard on a request only when no dashboard is connected - "connected" = the admin event
  stream has a live SSE client (`AdminEventStream.hasConnectedClients`), which is how it avoids
  reopening or stealing focus from an open tab. Default on; `autoOpenDashboard=false` disables it.
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

**The substituted `<v>` is a per-sandbox secret token, NOT the sandbox name** (F1 in
`docs/threat-model.md`; the earlier name-as-value design was forgeable). The daemon mints a random
`gvsbx-<base64url>` token per sandbox, registers it as `--value`, and keeps a private token &rarr;
name map; `SandboxIdentityApi.resolveSandboxName(headerValue)` maps an incoming token back to its
name. Do **not** change this back to the name "because unforgeability not secrecy": names are
public, so a name-as-value lets a rogue agent set the header to any name by hand and be believed -
the whole point is that the value is a secret a sandbox cannot know. A sandbox only ever holds the
placeholder; the proxy substitutes it to that sandbox's own token.

- Every agent's kit (`sandbox-kit/kits/goversbx-<agent>/`) sends the same
  **hardcoded literal** (`goversbx-sandbox-identity-placeholder-v1`) as the header value, never
  read from an env var. `claude mcp add --header` resolves its value once, at registration time,
  and freezes it into `~/.claude.json` - a per-sandbox env var that didn't exist yet at that moment
  would be frozen in as empty forever. A shared hardcoded literal has no such timing dependency:
  the daemon-side substitution rule can start applying at any later point, live. For pi, the
  literal must additionally never contain `${` or start with `!` - pi interpolates env vars and
  runs commands in MCP header values.
- A header value that **doesn't resolve to a token** (`SandboxIdentityApi.resolveSandboxName(...)`
  returns empty) is the signal that a sandbox isn't usable yet: the raw placeholder (not registered),
  a stale token (daemon restarted - the token in sbx outlives the in-memory map), or a forged value.
  `RequestNetworkAccessTool` checks this before doing anything else; if empty, it calls
  `registerAnyUnregisteredSandboxes()` (which runs `sbx ls` and registers every running sandbox this
  daemon doesn't already know about - not just the one that triggered it) and tells the agent
  to retry, without creating a `NetworkAccessRequest`.
- **Registrations are durable** (`SandboxRegistrationStore` port, `FileSandboxRegistrationStore`
  adapter): the registered incarnations (`name@id`) and the token -> name map are saved to
  `~/.local/state/goversbx/sandbox-registrations.json` (`GOVERSBX_STATE_DIR`, owner-only, atomic
  rename) after every successful registration and loaded at startup, so a restarted daemon resolves a
  registered sandbox's token on its very first call. Losing registrations on restart is **not
  acceptable** (the user's call): the secret in sbx outlives the daemon, and before this every
  restart cost each sandbox a "please retry" plus one `sbx ls` + `set-custom`, and an agent that had
  used up its retries gave up. Don't go back to in-memory-only. The reactive re-registration remains
  the fallback for a lost/corrupt file: the stale token doesn't resolve, one re-registration mints a
  fresh token and overwrites the sbx secret live, and the next retry works (contract-tested both
  ways in `SandboxIdentityTest`). The file is written through Jackson's tree model on purpose - no
  reflection on daemon types, so it needs no extra native-image metadata. `DaemonProcess` gives every
  test daemon its own `GOVERSBX_STATE_DIR`; a restart test passes the same one to two daemons.
- `GOVERSBX_SBX_LS_FORMAT_ARGS` (default `--format json`) exists because `sbx ls`'s JSON flag
  spelling changed across `sbx` versions (`--json` before v0.48.0) - don't hardcode one spelling if
  you touch `SbxCliSandboxListAdapter`.
- `SandboxSummary` (the `sbx ls` JSON row DTO) only declares `name`/`status` and ignores the rest
  via `@JsonIgnoreProperties(ignoreUnknown = true)` - this is deliberate, not an oversight; don't
  "complete" it with every field sbx happens to return.

The admin-only `POST /api/sandboxes/{name}/register` endpoint still exists for pre-warming a
sandbox manually, but nothing requires it anymore - don't assume it's the only path a sandbox gets
registered through.

- `--host localhost`, not `host.docker.internal`: sandboxes call the daemon at
  `host.docker.internal:8787`, but the sbx proxy matches that traffic as `localhost` (verified with
  `scripts/diagnose-substitution.sh`; `*.docker.internal` doesn't match either). `--host` takes no
  port, `--env` must be `^[A-Z_][A-Z0-9_]*$`.
- The flag is `--host`, not `--target` (`--target` only exists in an old example, docker/sbx-releases#7;
  the first real e2e run failed on it). sbx flag names have churned - prefer a real invocation from
  an issue or `sbx <cmd> --help` output over doc summaries.
- `registerAnyUnregisteredSandboxes()` registers every running sandbox matching
  `GOVERSBX_IDENTITY_SANDBOX_PATTERN` (default `.*`, i.e. all of them - including sandboxes that have
  nothing to do with goversbx). Narrow it for test runs.
- sbx keys custom secrets by placeholder (docker/sbx-releases#230) and we use one placeholder for
  every sandbox. That per-sandbox `--sandbox` scoping keeps them apart is exactly what step 4b of
  `scripts/e2e-claude.sh` checks against a real host - see `docs/status.md` for the fallback if not.

## Launcher (`scripts/goversbx`)

Thin on purpose (spec 0003 D6, `docs/vision.md`): its own verbs are `run`, `init`, `setup`,
`start`, `stop`, `status`, `logs`, `dashboard`, `version`, `help`, `upgrade` (reserved) and `sbx`;
**every other first argument is `exec sbx "$@"`** - don't add a verb for something sbx can do.
`run` = daemon, `setup --quiet`, scaffold, `sbx env plan` (the sandbox **name is read from its
`Name:` line**, never derived - sbx 0.53 no longer guarantees `<agent>-<dir>`), `sbx env create` +
register for a new sandbox, `exec sbx env run`. Never pass a path argument to `sbx env`: sbx then
skips `~/.sbxenv.yaml` and the goversbx kit with it. `run` only knows `--agent`, `--name`,
`--force`, `-- ARGS`; resist forwarding more flags (`goversbx env run ...` already does). Only
`sbxenv.yaml` is a project file - sbx does not read a `.sbxenv.yaml` dotfile in a project.
Tests: `scripts/testing/test-launcher.sh` against `fake-sbx` (which implements `version` and
`env plan|create|run|rm`) with `HOME` pointed at a temp directory.

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
- E2e suite (`daemon/src/e2eTest`, task `e2eTest`, not in `check`): black-box like `binaryTest`
  (no daemon classes on its classpath), but against a REAL `sbx` and the real jar in real mode. One
  shared fixture (`E2eEnvironment`, built once, closed once by `E2eSuite` via the root store): a
  restartable daemon with the sandbox listener on `0.0.0.0` and `GOVERSBX_IDENTITY_SANDBOX_PATTERN`
  limited to the run's prefix, plus sandboxes `<prefix>-a` (with workspace) and `<prefix>-b` (without),
  both created *before* the first registration so a collision between them would show. Classes and
  methods run in `@Order` (`junit-platform.properties`); the agent side always runs INSIDE a sandbox
  via `sbx exec` (`Sandbox.callTool`: curl + the placeholder header, through the proxy) - never from
  the test JVM, which would bypass the proxy and the whole point. Real agents (`claude -p`) are
  `@Tag("agent")` and skipped unless `GOVERSBX_E2E_AGENTS` names them. Don't read `SANDBOX_NAME` in
  it: inside a dev sandbox that is the OUTER sandbox's name.
- The `javalin-testtools` artifact version must track the `javalin` version exactly
  (`gradle/libs.versions.toml`) - they're versioned in lockstep by upstream.
- **Javalin 7 specifics** (migrated from 6 in 2026-10): routes are registered on `config.routes`
  (a `JavalinDefaultRoutingApi` with `get`/`post`/`before`/`sse`/`exception`) **inside**
  `Javalin.create(cfg -> { ... })`, not on the `Javalin` instance (`app.get(...)` no longer exists).
  So the controllers/filter take a `JavalinDefaultRoutingApi`, and `Main` registers them in the
  create lambda. Jetty is 12/EE10: `ServletHolder`/`ServletContextHandler` are
  `org.eclipse.jetty.ee10.servlet.*`. `SseClient.sendEvent` is `sendEvent(event, data, id)` (pass
  `null` id). `javalin-testtools` 7 dropped OkHttp - its `Response`/`Request.Builder` wrap
  `java.net.http`, and there's no raw-client accessor (drive SSE in tests with `java.net.http`
  directly). `Context`, `JavalinJackson`, `config.staticFiles.add`, `UnauthorizedResponse` are
  unchanged. JUnit (6.x), Jackson (2.x classic) and slf4j track latest.

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

`sandbox-kit/kits/goversbx/` is ONE real sbx **v3 mixin kit** for every agent (`goversbx.yaml` +
`goversbx.dockerfile` + `stage/`; spec: `docker/sandbox-kit-spec`), needs sbx >= 0.51 - since 0.51
the built-in agents are v3 kits and v1/v2 kits cannot be combined with them, which is why the four
v2 kits (`goversbx-claude|pi|omp|opencode`) are gone. Applied at create time
(`sbx create claude --kit sandbox-kit/kits/goversbx --name N <ws>`, or as an entry under `kits:` in
`~/.sbxenv.yaml` for `sbx env`), or added to an existing all-v3 sandbox with
`sbx kit add <name> <kit dir>` - that asks to approve the kit's network rules and needs a terminal
(no flag skips it). sbx builds the source directory on the host with its kit builder. The daemon is
agent-agnostic - anything that speaks MCP over Streamable HTTP and sends the placeholder header
works - so adding an agent means adding a section to `register-mcp.sh`, not touching `daemon/`.
Design and measurements: `docs/specs/0003-sbx-env-native/epic.md` (Evidence E1-E19).

- **Stay inside what `sbx kit add` can apply**: network allow rules, a bundled skill, install and
  startup hooks, agent context. No `credential@1`, `volume@1`, `port@1`, lifecycle `files`, deny
  rules - any of those makes the kit create-only.
- The payload lives under `/usr/share/goversbx/` (a prefix no other kit names), staged from
  `stage/` by a dockerfile with no base image (`FROM scratch` + `COPY --chmod`), so the build needs
  no pull. A v3 kit has no `files/` channel - content only comes from its dockerfile.
- The kit must be self-contained (its build context is the kit directory), so it carries a **copy**
  of the shared skill. The source of truth is `sandbox-kit/skill/goversbx-network-access/`; after
  editing it run `./gradlew :sandbox-kit:syncKitSkills`. `check` fails (`verifyKitSkills`) if the
  copy has drifted.
- Args `daemonHost` (default `host.docker.internal`) and `daemonPort` (default `8787`), exported as
  `GOVERSBX_DAEMON_HOST/_PORT` (`args.<name>.env` - v3 has no `environment:` block); the kit allows
  `localhost:<port>` plus `<daemonHost>:<port>` - the sbx proxy matches sandbox traffic to
  `host.docker.internal` as `localhost`. Override with `--kit-arg goversbx.daemonPort=...`.
- The skill is declared with `agent-skill@1` (`optional`: a workload without a skills directory,
  e.g. `shell`, would otherwise refuse the kit). The runtime links it into every skills directory
  the composition declares (`~/.claude/skills`, omp-sbx's `~/.omp/agent/skills`);
  `register-mcp.sh` links it for agents whose directory the runtime does not know.
- Exactly ONE `agent-context` version: a kit declaring `@1` and `@2` creates fine but breaks
  `sbx env run` on the existing sandbox ("competing agent-context versions", sbx 0.51.0).
- `stage/usr/share/goversbx/register-mcp.sh` is both the install hook (race-free, before the agent
  reads its config - the pattern the sbx `claude` kit uses for `mcp-gateway`) and the startup hook
  (add-only fallback; startup hooks run next to a live session). It registers the endpoint for each
  agent it finds and skips the rest; a malformed existing config fails that agent's step loudly and
  is never overwritten. An agent installed after create is picked up on the next start, or by
  running the script once (`sbx exec <name> sh /usr/share/goversbx/register-mcp.sh`).
  - **Claude Code**: `claude mcp add --transport http --header ... --scope user || true`, plus a
    marker-wrapped "use goversbx for blocked hosts" rule in `~/.claude/CLAUDE.md` (user memory,
    always loaded): the sandbox's own `CLAUDE.md` tells Claude to ask the user to run
    `sbx policy approval`, and on a real host Claude followed that instead of the skill. The kit's
    agent context does not help here - with `@1` and `@2` alike it only becomes a pointer under
    `## Kits` to `~/.claude/kits-agent-context/goversbx.md`.
  - **opencode**: merges a `"type": "remote"` entry into `~/.config/opencode/opencode.json` with
    `jq` (opencode is a standalone binary, no Node guaranteed); skill in `~/.agents/skills`.
  - **pi** (>= 1.0): merges into the user-level `~/.pi/agent/mcp.json` (a project's `.pi/mcp.json`
    is only read once trusted) with `"exposure": "direct"` (pi's default hides MCP tools from the
    model's tool list); skill in `~/.agents/skills`.
  - **oh-my-pi**: merges a `"type": "http"` entry into `<agentDir>/mcp.json`. `<agentDir>` is
    profile-scoped: `~/.omp/agent` by default, `~/.omp/profiles/<name>/agent` under
    `OMP_PROFILE`/`PI_PROFILE`, `PI_CODING_AGENT_DIR` overrides the default, `OMP_AGENT_DIR` forces
    a path; the skill goes into its `skills/`.
  - pi and omp merges run with `node`, else `bun` (omp is a Bun app); with neither they are skipped.
- The endpoint is registered in the agent, NOT through the sbx MCP gateway (`sbx mcp add`): gateway
  calls start on the host and bypass the sandbox proxy, so the daemon would see the raw placeholder
  from every sandbox - no identity (measured on 0.49.1 and 0.51.0, also for `command` servers).
- The kit does not start the daemon, and cannot: kit hooks run inside the sandbox, while the daemon
  needs the host's `sbx` CLI and is the approval authority the sandbox must not control.
- The skill names tools without assuming a prefix: pi exposes them as
  `mcp__goversbx-network__<tool>`, omp as `mcp__goversbx_network_<tool>`.
- Header values are interpreted by some agents: pi and omp expand `${VAR}` and run `!command`,
  omp also substitutes a value that is exactly the name of a set env var, opencode expands
  `{env:VAR}`. Keep the placeholder a plain literal that is none of these.

Verification state (2026-11-10, sbx 0.51.0 in the nested dev sandbox): the kit builds and composes
with the `claude`, `opencode` and `shell` workloads; Claude and opencode registration, skill link,
args and the `CLAUDE.md` rule were checked inside real sandboxes; `sbx kit add` onto an existing
claude sandbox worked. `register-mcp.sh` was run against a fake home for all four agents (fresh,
existing, malformed config, rerun changes nothing). **Not verified with the v3 kit**: real pi and
real omp (the scripts `scripts/e2e-pi.sh`/`e2e-omp.sh`/`e2e-opencode.sh`/`e2e-claude.sh` were only
adapted mechanically and not rerun), and composition with omp-sbx's `omp` + `omlx-mixin`.
