# goversbx

Lets an agent running inside an `sbx`-managed Docker sandbox proactively request outbound
network access to a specific host, with a human approving or denying the request from a small
local web UI. On approval, the daemon - which runs entirely outside every sandbox - shells out to
the `sbx` CLI to actually grant it (`sbx policy allow network <host> --sandbox <name>`).

The daemon also serves the MCP protocol surface sandboxes talk to, over HTTP, the same way the
platform's own `mcp-gateway` does - there is no process or jar running inside the sandbox at all.

See [`docs/plan.md`](docs/plan.md) for the full design and rationale, including the 2026-10-14
revisions that moved the MCP server into the daemon and added verified sandbox identity via
`sbx secret set-custom`.

## Modules

| Module        | Runs where              | Purpose                                                             |
|---------------|--------------------------|-----------------------------------------------------------------------|
| `daemon`      | host, outside sandboxes | Everything: the approval workflow, the MCP endpoint, and sandbox-identity registration, as three bounded contexts (see below) |
| `sandbox-kit` | inside each sandbox, via sbx | sbx mixin kits (Claude Code, pi, oh-my-pi) that register the daemon's MCP endpoint, allow the sandbox to reach it, and install the skill |

### Inside `daemon`: bounded contexts, hexagonal layering

- `networkaccess` - the approval workflow. `domain` (entity + invariants) →
  `application` (use-case service + driven ports) → `adapter` (in-memory repository, `sbx` CLI
  adapter, admin web controller). Publishes a narrow `api` package
  (`NetworkAccessApi` + `NetworkAccessRequestView`) - the **only** thing another bounded context
  may depend on.
- `mcp` - pure MCP-protocol translation against `networkaccess.api`. Has no domain of its own by
  design; it exists purely to adapt MCP tool calls into calls on the published API.
- `sandboxidentity` - registers a sandbox's proxy-verified identity secret (`sbx secret
  set-custom`), reactively: the first time `mcp` sees a request whose sandbox-name header is still
  the raw, unsubstituted placeholder, it calls `sandboxidentity.api.SandboxIdentityApi` (the one
  thing `mcp` is allowed to depend on here), which lists every running sandbox via `sbx ls` and
  registers any this daemon process doesn't know about yet. No operator step required for a new
  sandbox's normal startup - an admin-only `POST /api/sandboxes/{name}/register` endpoint remains
  for pre-warming one manually.

## Building

Requires a JDK on `PATH` (JDK 21+; bytecode is compiled with `--release 21` regardless of the
JDK actually running Gradle, so no JDK 21 toolchain download is needed).

```bash
./gradlew build
```

Runs the full test suite too: fast JUnit 5 unit tests for the domain/application layers, plus
`javalin-testtools`-backed integration tests that boot the real admin REST API and the real MCP
HTTP endpoint (driven by the MCP Java SDK's own client) against an ephemeral port.

## Install on macOS (Homebrew)

Apple Silicon only. The daemon is a GraalVM native binary - no JVM needed.

```bash
brew tap fabian-dot-run/goversbx
brew install goversbx
brew services start goversbx        # daemon at login (launchd); or `goversbx start` on demand
goversbx run claude ~/my-project    # start a sandbox with the kit
```

Admin UI: http://127.0.0.1:8788 (or `goversbx dashboard` to open it in your browser). The installed
`goversbx` is the same launcher as `scripts/goversbx` below; it runs the native `goversbx-daemon` and
uses the kits shipped with the formula. The release tarball (`goversbx-<version>-darwin-arm64.tar.gz`)
works unpacked as is.

### Releasing

Push a tag `v<version>`. `.github/workflows/release.yml` builds the native binary on an Apple
Silicon runner (native-image can't cross-compile), smoke-tests it, attaches the tarball to a GitHub
release and updates `Formula/goversbx.rb` in the tap repo (rendered from
`packaging/homebrew/goversbx.rb.template`; setup notes at the top of the workflow). Locally, on any
platform: `GRAALVM_HOME=... scripts/package-release.sh`.

### Testing the native binary

`daemon/src/binaryTest` is a black-box contract suite: it starts the daemon as a process and talks
to it only over HTTP/MCP, against a scriptable fake `sbx`. It covers the admin UI (every file, byte
for byte), the admin API and its error paths, SSE, MCP over raw Streamable HTTP (every protocol
version, both tools, schema validation, sessions), every client capability the MCP SDK knows
(generated from the SDK's own types), the official MCP Java SDK client, traffic recorded from real
Claude Code, pi and opencode (`client-fixtures/`, re-record with
`scripts/testing/record-client-fixtures.sh`), sbx failures and concurrency.

```bash
./gradlew :daemon:binaryTestJar        # against the jar (also part of ./gradlew check)
./gradlew :daemon:binaryTestNative     # against the native binary (GRAALVM_HOME + C toolchain)
```

native-image needs reflection/resource metadata for everything the daemon touches reflectively.
It isn't maintained by hand: `nativeCompile` depends on `nativeMetadata`, which runs this suite
against the jar under GraalVM's tracing agent and builds the binary from that trace. A code path
the suite doesn't exercise has no metadata, so it would fail only in the binary: new behavior
needs a test here.

## Quick start (repo checkout)

```bash
scripts/goversbx run claude ~/my-project        # = start daemon if needed, then sbx run --kit ... claude ~/my-project
scripts/goversbx run --name my-sb claude ~/my-project
scripts/goversbx run --goversbx-kit pi --name pi-sb shell ~/my-project
scripts/goversbx dashboard                       # open the admin UI in your default browser
scripts/goversbx status | logs | stop
```

`goversbx dashboard` opens the admin UI at the configured admin port (handy when
`GOVERSBX_ADMIN_PORT` isn't the default `8788`, so you don't have to remember the URL); it refuses
if the daemon isn't running.

`scripts/goversbx` builds the jar if it's missing or stale, starts the daemon in the background for
real (`GOVERSBX_SBX_DRY_RUN=false` unless set; log and pid file in `~/.local/state/goversbx/`), waits
for both health endpoints, and then hands over to `sbx run --kit sandbox-kit/kits/goversbx-<kit>`.
The daemon keeps running after the session ends - it serves every sandbox - until
`scripts/goversbx stop`. For a sandbox that already exists it runs `sbx run` without `--kit` and
tells you to recreate it if you want the kit (see below). All other `GOVERSBX_*` variables below pass
through.

## Running the daemon

```bash
./gradlew :daemon:shadowJar
java -jar daemon/build/libs/goversbx-daemon.jar
```

Two HTTP listeners start:

- **Sandbox-facing** — `0.0.0.0:8787` by default (`GOVERSBX_SANDBOX_API_PORT`,
  `GOVERSBX_SANDBOX_API_BIND`). Hosts only the MCP endpoint at `/mcp` plus `/api/health`. This is
  what sandboxes reach via the always-allow-listed hostname - there is nothing else to install
  inside a sandbox.
- **Admin UI + API** — `127.0.0.1:8788` only (`GOVERSBX_ADMIN_PORT`), never exposed to a
  sandbox. Open it with `goversbx dashboard` (it carries the admin token, see below) to approve/deny
  pending requests. It updates live (server-sent events, `GET /api/events`), and after "Enable
  notifications" it raises a browser notification for every new request with Approve/Deny buttons
  (via a service worker; buttons need Firefox 152+ or Chrome - otherwise clicking the notification
  opens the request). Keep a tab open; on macOS the browser itself must be allowed to send
  notifications (System Settings > Notifications).

The admin API is **token-gated**: the loopback bind alone is not a boundary, because the sbx proxy
can re-originate a sandbox's traffic to the host's loopback (see `docs/threat-model.adoc`, F3). The
daemon generates a token at startup, stores it in `~/.local/state/goversbx/admin-token`
(`GOVERSBX_ADMIN_TOKEN` overrides it), and requires it on every admin API/SSE call via
`Authorization: Bearer …` or `?token=…`. Static files and `/api/health` stay public. `goversbx
dashboard` reads the token and opens an authenticated URL, so you normally never see it; opening
`127.0.0.1:8788` by hand shows a "not authenticated" notice.

Sandbox registrations (which sandboxes already carry an identity secret, and the tokens behind
them) are kept in `~/.local/state/goversbx/sandbox-registrations.json` (`GOVERSBX_STATE_DIR`
moves the whole state dir), so a daemon restart - upgrade, `goversbx stop && start`, reboot - does
not cost every sandbox a "please retry" round trip; a registered sandbox's first call after the
restart just works. The file holds secrets and is owner-only; delete it and the daemon falls back to
registering each sandbox again on its next call.

By default `GOVERSBX_SBX_DRY_RUN=true`, so approvals and sandbox-identity registration log what
they *would* run without calling `sbx`. Set it to `false` (and `GOVERSBX_SBX_BINARY` if `sbx` isn't
on `PATH`) to apply real changes.

### Configuration file

On first start the daemon writes a commented config file to `~/.config/goversbx/config.properties`
(`$XDG_CONFIG_HOME` is honored); edit it and restart the daemon to apply. Environment variables
(`GOVERSBX_*`) override the file.

- `autoOpenDashboard` (default `true`) — when a request arrives and **no dashboard is currently
  connected**, the daemon opens the dashboard in your default browser (authenticated). It never opens
  a second window or steals focus while a dashboard is already open: the daemon knows a dashboard is
  open because it holds a live SSE connection. Set it to `false` to rely only on a tab you keep open
  and its notifications. Env override: `GOVERSBX_AUTO_OPEN_DASHBOARD`.
- `autoOpenCooldownMillis` (default `10000`) — how long after an auto-open before it may open again,
  to absorb a burst of requests while the new tab is still connecting.

### Sandbox identity

Every sandbox's MCP registration (whatever the agent) sends the *same hardcoded placeholder literal*
(`goversbx-sandbox-identity-placeholder-v1`) as `X-Goversbx-Sandbox-Name` - never the real name,
and never read from a per-sandbox env var (see `docs/plan.md`'s third 2026-10-14 revision for why
a hardcoded literal, not an env-var lookup, is the whole point). The first time the daemon sees
that literal arrive unsubstituted from some sandbox, it automatically lists every running sandbox
(`sbx ls`) and registers any it doesn't know about yet via `sbx secret set-custom --host
localhost --value <name>`, then asks the agent to retry. (`localhost`, not `host.docker.internal`:
sandboxes reach the daemon at `host.docker.internal:8787`, but the sbx proxy matches that traffic as
`localhost` - verified with `scripts/diagnose-substitution.sh`.) From then on, the sbx proxy rewrites
the placeholder to that sandbox's real name on every outbound header to the daemon - the sandbox
itself never sees or controls the real value. No operator action is required for this to happen.

To pre-warm a sandbox manually (skips the one "please retry" round trip on its first call):

```bash
curl -X POST http://localhost:8788/api/sandboxes/<sandbox-name>/register
```

`GOVERSBX_IDENTITY_TARGET_HOST`/`_ENV_VAR`/`_PLACEHOLDER` customize the registration details;
`GOVERSBX_IDENTITY_SANDBOX_PATTERN` (regex, default `.*`) limits which running sandboxes get
registered automatically (the e2e test uses it to leave the host's other sandboxes alone);
`GOVERSBX_SBX_LS_FORMAT_ARGS` (default `--format json`) customizes the `sbx ls` invocation, since
that flag's spelling changed across `sbx` versions (`--json` before v0.48.0).

To register the MCP endpoint in a local Claude Code session for manual testing:

```bash
claude mcp add goversbx-network "http://localhost:8787/mcp" --transport http \
  --header "X-Goversbx-Sandbox-Name: my-sandbox" --scope user
```

Exposes two tools: `request_network_access(host, reason, timeoutSeconds?)` and
`check_network_access_status(requestId)`.

## The sbx kits

`sandbox-kit/kits/` holds one sbx v2 **mixin kit** per agent. Start the daemon on the host first -
a kit runs inside the sandbox and cannot start it - then create sandboxes with the kit:

```bash
sbx create --kit sandbox-kit/kits/goversbx-claude --name my-sandbox claude ~/my-project
# or in one step: sbx run --kit ...
```

The kit must be applied at **create** time. `sbx kit add` does *not* work for goversbx kits: they
ship files (the skill, and the pi/omp register scripts), and `sbx kit add`'s recreate flow doesn't
apply a kit's files (`error: kit "…" declares files, which the kit-add recreate flow does not yet
apply`). To put the kit on an existing sandbox, recreate it: `sbx rm <name>` then
`sbx create --kit … <name> …`.

| Kit | Agent | What it does |
|-----|-------|--------------|
| `goversbx-claude` | Claude Code (`requires: claude`) | `claude mcp add --transport http --header ...` at install time (+ startup fallback), skill in `~/.claude/skills/` |
| `goversbx-pi` | pi >= 1.0 | Merges a `goversbx-network` entry (`"exposure": "direct"`) into `~/.pi/agent/mcp.json`, skill in `~/.agents/skills/` |
| `goversbx-omp` | oh-my-pi | Merges a `"type": "http"` entry into the active profile's `mcp.json`, skill in its `skills/` |

All three allow the sandbox to reach the daemon (`permissions.network.allow`) and take two kit args,
`daemonHost` (default `host.docker.internal`) and `daemonPort` (default `8787`), e.g.
`--kit-arg goversbx-claude.daemonPort=9787`. sbx has no pi/omp agent, so those two kits go on a
sandbox where the agent is installed (e.g. `shell`).

The skill's source of truth is `sandbox-kit/skill/`; each kit carries a copy because kits must be
self-contained. After editing the skill run `./gradlew :sandbox-kit:syncKitSkills` (`check` fails
on drift). `./gradlew :sandbox-kit:packageKits` writes one zip per kit to
`sandbox-kit/build/distributions/`.

### Using plain `sbx` without the `goversbx` launcher

The `goversbx` CLI is only convenience: at its core it does two things — start the daemon if needed,
then call `sbx run --kit …`. **Once the daemon is already running, you can skip the launcher entirely
and use normal `sbx`**, just by passing the kit path:

```bash
# new sandbox:
sbx create --kit <kits-dir>/goversbx-claude --name my-sb claude ~/project
# or start one in a single step:
sbx run --kit <kits-dir>/goversbx-claude claude ~/project
# existing sandbox: recreate it with the kit (sbx kit add can't apply goversbx kits - they ship files):
sbx rm my-sb && sbx create --kit <kits-dir>/goversbx-claude --name my-sb claude ~/project
```

`<kits-dir>` is:

- **repo checkout:** `sandbox-kit/kits/goversbx-<agent>` (`goversbx-claude`, `goversbx-pi`,
  `goversbx-omp`, `goversbx-opencode`).
- **Homebrew / tarball install:** `share/goversbx/kits/` under the install prefix (what the launcher
  resolves as `GOVERSBX_KITS_DIR`, i.e. `../share/goversbx/kits` next to the `goversbx` binary; for
  Homebrew, `"$(brew --prefix)"/share/goversbx/kits`). The kits are self-contained, so the path works
  as-is.

Two things the launcher otherwise handles for you:

- **The daemon must be running** — a kit can't start it (kit hooks run inside the sandbox; the daemon
  needs the host's `sbx` CLI and stays outside the sandbox). Start it once with `goversbx start` (or
  `brew services start goversbx`).
- **Non-default port** — if your daemon isn't on the default `8787`, append
  `--kit-arg goversbx-<agent>.daemonPort=<port>` (the launcher sets this automatically). On `8787`
  nothing extra is needed.

Everything else — the network rule to reach the daemon and the MCP registration — the kit brings
itself.

## Status

**Current state, the Claude Code MVP test runbook, and open questions: [`docs/status.md`](docs/status.md).**
End-to-end test against a real sbx host: `scripts/e2e-claude.sh` (run on the host).

**The Claude Code MVP is verified end to end against a real sbx host (2026-10-14):** reactive
sandbox identity, proxy substitution, approval -> `sbx policy allow` -> host reachable, identity
isolation between sandboxes, and real Claude Code calling the tools. Automated: unit tests plus
`javalin-testtools` integration tests for the admin API, the registration endpoint and the MCP
endpoint (`./gradlew check`). The Claude kit is verified on the host too (`sbx create --kit`, nothing installed by hand).
