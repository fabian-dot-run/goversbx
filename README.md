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
| `sandbox-kit` | inside each sandbox, via sbx | one sbx v3 mixin kit for every agent (Claude Code, opencode, pi, oh-my-pi): registers the daemon's MCP endpoint, allows the sandbox to reach it, and brings the skill |

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
brew services start goversbx        # daemon at login (launchd); or let `goversbx run` start it
cd ~/my-project && goversbx run     # asks once for your default agent, then creates and attaches
```

Admin UI: http://127.0.0.1:8788 (or `goversbx dashboard` to open it in your browser). The installed
`goversbx` is the same launcher as `scripts/goversbx` below; it runs the native `goversbx-daemon` and
uses the kits shipped with the formula. The release tarball (`goversbx-<version>-darwin-arm64.tar.gz`)
works unpacked as is.

### Releasing

Add the version's section to `CHANGELOG.md`, bump the version (`build.gradle.kts`, the MCP server info
in `McpContext`), then push a tag `v<version>`.
`.github/workflows/release.yml` builds the native binary on an Apple Silicon runner (native-image
can't cross-compile), smoke-tests it, attaches the tarball to a GitHub release whose notes are that
changelog section (`scripts/release-notes.sh`), and updates `Formula/goversbx.rb` in the tap repo
(rendered from `packaging/homebrew/goversbx.rb.template`; its caveats link to the release notes;
setup notes at the top of the workflow). Locally, on any platform:
`GRAALVM_HOME=... scripts/package-release.sh`.

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

### End-to-end suite against a real sbx

`daemon/src/e2eTest` is the end-to-end suite: it starts the daemon jar **for real**
(`GOVERSBX_SBX_DRY_RUN=false`), creates two throwaway sandboxes with the Claude kit (`sbx create
--kit ...`), and checks every link of the chain from *inside* those sandboxes, through the sbx proxy:
the sbx CLI still has the shape the daemon assumes; what the kit installed (`claude mcp list`, skill,
`~/.claude/CLAUDE.md` rule, the placeholder header); raw placeholder -> reactive registration ->
pending request under the real sandbox name -> approval -> `sbx policy allow` -> host reachable, and
only from that sandbox; identity isolation between the two sandboxes; denial; polling; the tool's
refusals; registrations surviving a daemon restart and recovering from a lost registration file; and,
opt-in, real Claude Code picking goversbx on its own.

```bash
./gradlew :daemon:e2eTest                                   # sbx from PATH; report in daemon/build/reports/tests/e2eTest
GOVERSBX_E2E_SBX=/usr/bin/sbx GOVERSBX_E2E_AGENTS=claude ./gradlew :daemon:e2eTest
```

It needs a signed-in `sbx`, pulls images, changes sandbox policies and takes a few minutes, so it is
not part of `check`. It only registers and touches sandboxes named `goversbx-e2e-<run>-*`, and removes
them at the end (`GOVERSBX_E2E_KEEP=1` keeps them). The target hosts (`GOVERSBX_E2E_TARGET_HOST`,
default `example.org`, and `GOVERSBX_E2E_DENY_HOST`, default `example.net`) must be blocked for new
sandboxes before the run. All knobs are documented in `E2eConfig`. Logs and the daemon's state dir
land in `daemon/build/e2e/<run>/`.

It runs on the host, or inside a `--nested` dev sandbox with `docker-sbx` installed (see
`docs/status.md`, "Nested sbx dev sandbox"), which is how the agent runs it without a host round trip.
The older per-agent scripts `scripts/e2e-{claude,pi,omp,opencode}.sh` remain for the pi, oh-my-pi and
opencode kits.

## Quick start (repo checkout)

```bash
cd ~/my-project
scripts/goversbx run                # daemon up, user defaults checked, sandbox created via `sbx env`, agent attached
scripts/goversbx run --agent codex  # in a new project: another agent than your default
scripts/goversbx dashboard          # open the admin UI in your default browser
scripts/goversbx status | logs | stop
scripts/goversbx ls                 # every other verb is sbx's: `sbx ls`, `sbx policy ...`, `sbx env ...`
```

`goversbx run` is the whole setup of a project (sbx 0.51 or newer):

1. starts the daemon if it is not running (for real, `GOVERSBX_SBX_DRY_RUN=false` unless set; log and
   pid file in `~/.local/state/goversbx/`; a repo checkout builds the jar when it is missing or stale);
2. `goversbx setup`: keeps **one entry for the goversbx kit in `~/.sbxenv.yaml`**, sbx's user
   defaults, pointing at this install (and `args.daemonPort` when the daemon is not on 8787). The
   first time it asks which agent new sandboxes start by default, and never again. Everything else
   in that file is yours and stays byte for byte; the previous content is kept in
   `~/.sbxenv.yaml.goversbx-bak`. Because the kit lives in the user defaults, a plain `sbx env run`
   in any project gets goversbx too;
3. `goversbx init`: writes `sbxenv.yaml` (`schemaVersion`, `workspace: .`) if the directory has none.
   An existing file is never touched - put project kits, `env`, a `name` or another `agent` there;
4. creates the sandbox with `sbx env create` (sbx shows its plan and asks; `--force` approves),
   registers it with the daemon so its identity is in place before the agent's first call, and
   attaches with `sbx env run`. An existing sandbox is just attached.

| Verb | What it does |
|---|---|
| `run [--agent A] [--name N] [--force] [-- AGENT ARGS]` | the steps above; for sbx's other flags use `goversbx env run ...` |
| `init [--agent A]` | step 3 alone |
| `setup [--agent A] [--check]` | step 2 alone; `--check` only reports (exit 2 on findings) |
| `start`, `stop`, `status`, `logs`, `dashboard`, `version` | the daemon; `status` also says whether the user defaults are in order |
| `sbx ARGS...` | runs `sbx ARGS...` - the way to the sbx verbs goversbx has too (`run`, `stop`, `setup`, `version`, `help`), e.g. `goversbx sbx stop my-sandbox` |
| anything else | passed to sbx unchanged |

The old form `goversbx run <agent> <path>` is gone (0.6.0): `cd` into the project and run
`goversbx run`. To put the kit onto a sandbox that already exists, run
`sbx kit add <name> <kit dir>` in a terminal (`goversbx upgrade` will do this). To remove goversbx
from sbx, delete its entry under `kits:` in `~/.sbxenv.yaml` - Homebrew has no uninstall hook that
could. `goversbx dashboard` opens the admin UI at the configured admin port and refuses if the
daemon isn't running. The daemon keeps running after a session ends - it serves every sandbox -
until `goversbx stop`. All other `GOVERSBX_*` variables below pass through.

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
localhost --value-stdin` (a per-sandbox token on stdin, never in the process arguments), then asks
the agent to retry. (`localhost`, not `host.docker.internal`:
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

## The sbx kit

`sandbox-kit/kits/goversbx/` is one sbx **v3 mixin kit** for every agent. It needs **sbx 0.51 or
newer**: since 0.51 the built-in agents are v3 kits, and sbx refuses to combine them with v1/v2 kits
(which is what goversbx shipped up to 0.4.0). Start the daemon on the host first - a kit runs inside
the sandbox and cannot start it - then create sandboxes with the kit:

```bash
sbx create claude --kit sandbox-kit/kits/goversbx --name my-sandbox ~/my-project
# or in one step: sbx run claude --kit ...
```

sbx builds the kit from the directory on first use (a second or two; it has no base image, so
nothing is pulled). What it brings into the sandbox:

| Part | How |
|------|-----|
| Network rule to reach the daemon | `network-policy@1`: `localhost:<daemonPort>` and `<daemonHost>:<daemonPort>` |
| The `goversbx-network-access` skill | `agent-skill@1`: linked into the agent's skills directory by sbx (`~/.claude/skills/`, ...) |
| MCP registration | install + startup hook `/usr/share/goversbx/register-mcp.sh`, for every agent it finds: Claude Code (`claude mcp add`), opencode (`~/.config/opencode/opencode.json`), pi (`~/.pi/agent/mcp.json`), oh-my-pi (`<agentDir>/mcp.json`) |
| "Use goversbx for blocked hosts" | agent context, plus an always-loaded rule in `~/.claude/CLAUDE.md` for Claude |

Two kit args, `daemonHost` (default `host.docker.internal`) and `daemonPort` (default `8787`), e.g.
`--kit-arg goversbx.daemonPort=9787`. It composes with other v3 mixins (repeat `--kit`, or list them
under `kits:` in an `sbxenv.yaml`).

sbx has no pi/omp agent: layer the kit onto a sandbox where the agent is installed (e.g. `shell`, or
an omp workload kit). An agent installed *after* the sandbox was created is picked up on the next
start, or right away with `sbx exec <name> sh /usr/share/goversbx/register-mcp.sh`.

**Existing sandbox:** `sbx kit add <name> <kit-dir>` adds the kit without losing the sandbox's
files. It asks you to approve the kit's network rules, so it needs a terminal (there is no flag to
skip the question), and the sandbox must have been created by sbx 0.51 or newer - older ones have
to be recreated (`sbx rm <name>`, then create with `--kit`).

The MCP endpoint is registered in the agent itself, not through sbx's MCP gateway (`sbx mcp add`):
the gateway calls the daemon from the host, past the sandbox proxy that substitutes the identity
placeholder, so the daemon could not tell sandboxes apart.

The skill's source of truth is `sandbox-kit/skill/`; the kit carries a copy because a kit must be
self-contained. After editing the skill run `./gradlew :sandbox-kit:syncKitSkills` (`check` fails
on drift). `./gradlew :sandbox-kit:packageKits` writes `goversbx.zip` to
`sandbox-kit/build/distributions/`.

### Using plain `sbx` without the `goversbx` launcher

The `goversbx` CLI is only convenience: at its core it does two things — start the daemon if needed,
then call `sbx create --kit …`. **Once the daemon is already running, you can skip the launcher
entirely and use normal `sbx`**, just by passing the kit path:

```bash
# new sandbox:
sbx create claude --kit <kit-dir> --name my-sb ~/project
# or start one in a single step:
sbx run claude --kit <kit-dir> ~/project
# existing sandbox (created by sbx >= 0.51), in a terminal:
sbx kit add my-sb <kit-dir>
```

`<kit-dir>` is:

- **repo checkout:** `sandbox-kit/kits/goversbx`.
- **Homebrew / tarball install:** `share/goversbx/kits/goversbx` under the install prefix (what the
  launcher resolves from `GOVERSBX_KITS_DIR`, i.e. `../share/goversbx/kits` next to the `goversbx`
  binary; for Homebrew, `"$(brew --prefix)"/share/goversbx/kits/goversbx`).

Two things the launcher otherwise handles for you:

- **The daemon must be running** — a kit can't start it (kit hooks run inside the sandbox; the daemon
  needs the host's `sbx` CLI and stays outside the sandbox). Start it once with `goversbx start` (or
  `brew services start goversbx`).
- **Non-default port** — if your daemon isn't on the default `8787`, append
  `--kit-arg goversbx.daemonPort=<port>` (the launcher sets this automatically). On `8787`
  nothing extra is needed.

Everything else — the network rule to reach the daemon and the MCP registration — the kit brings
itself.

## Status

**Current state, the Claude Code MVP test runbook, and open questions: [`docs/status.md`](docs/status.md).**
End-to-end test against a real sbx: `./gradlew :daemon:e2eTest` (see "End-to-end suite" above).

**The Claude Code MVP is verified end to end against a real sbx host (2026-10-14):** reactive
sandbox identity, proxy substitution, approval -> `sbx policy allow` -> host reachable, identity
isolation between sandboxes, and real Claude Code calling the tools. Automated: unit tests plus
`javalin-testtools` integration tests for the admin API, the registration endpoint and the MCP
endpoint (`./gradlew check`). The Claude kit is verified on the host too (`sbx create --kit`, nothing installed by hand).
