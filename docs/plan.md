# goversbx — MVP: Network-Access Requests for Docker-SBX Sandboxes

## Context

Agents run inside `sbx`-managed Docker sandboxes with restricted outbound network access by
default. Today, when a blocked request requires approval, `sbx` surfaces a *reactive* pending
approval that a human resolves via `sbx policy approval ls/inspect/respond` on the host — the
agent only discovers it needs permission after already failing.

This project adds a *proactive* path: an MCP server inside the sandbox gives the agent an explicit
tool to request access to a host, with a reason, before or after a failure. A daemon running
outside the sandbox (on the host) receives these requests, shows them to a human in a small local
web UI, and — on approval — shells out to `sbx policy allow network <host> --sandbox <name>` to
actually grant it. For this MVP, approvals are permanent (no revocation/expiry).

Decisions already settled with the user:
- **Language**: Java, using the official MCP Java SDK, for both the MCP server and the daemon.
- **Sandbox → daemon channel**: a fixed, always-allow-listed network hostname/port (not a mounted
  Unix socket).
- **Human decision UI**: a local web UI (not CLI-only).
- Single monorepo containing both deployables plus the sandbox-customization assets.

Read-only recon of this very sandbox (not a hypothesis — observed directly) grounds the
sandbox-customization design:
- `/etc/durable-startup.d/<NNN-name>/*-cmd.sh` + a generated `manifest.json` is the startup-hook
  mechanism `sbx` already uses (e.g. the existing `claude` kit registers the `mcp-gateway` MCP
  server there via `claude mcp add ... --scope user`, run as the `agent` user).
- MCP servers end up registered via the `claude mcp add` CLI into `~/.claude.json`, not a static
  file we author directly.
- Skills are delivered via a bind-mounted `~/.claude/skills/synced/<bucket>/<skill>/SKILL.md`
  (YAML frontmatter + body), mirroring the format of built-in skills like `pdf`.

**Open/unverified**: the *host-side* mechanism for authoring a new "kit" or skill bucket so it
gets baked/mounted into newly created sandboxes is not visible from inside a sandbox. This is
flagged explicitly below (§5, M4) and must be checked against real `sbx` docs/source on the user's
host before that milestone — everything up to M4 does not depend on it.

## Recommended Approach

### 1. Repo layout — Gradle (Kotlin DSL) multi-module monorepo

```
goversbx/
  settings.gradle.kts            # includes common, daemon, mcp-server
  build.gradle.kts               # root config, Java 21 toolchain, shadow plugin
  gradle/libs.versions.toml      # jackson, javalin, mcp-sdk, junit, shadow
  gradlew / gradlew.bat / gradle/wrapper/...
  common/        run.fabian.common       # shared model + JSON, no I/O
  daemon/        run.fabian.daemon       # host-side process
  mcp-server/    run.fabian.mcpserver    # sandbox-side process
  sandbox-kit/                             # packaging task output, not compiled code:
                                            # SKILL.md, launcher script, durable-startup draft
```

Gradle over Maven: two independent fat jars + a non-code asset bundle fit Gradle's shadow plugin
and version catalogs more naturally than Maven's shade/multi-module setup, and no Gradle is
preinstalled so the wrapper is checked in.

### 2. MCP server (`mcp-server` module)

- **Transport: stdio**, registered via `claude mcp add goversbx-network <launcher> --scope user`
  — matches how `claude` CLI already wires local MCP processes. (Alternative considered: register
  the daemon itself as an HTTP-transport MCP endpoint, like `mcp-gateway` is; deferred rather than
  adopted now since it would require the MCP Java SDK's HTTP/SSE provider on the daemon and
  removes the `mcp-server` module entirely — worth revisiting at M4, not required for MVP.)
- stdout is reserved for JSON-RPC frames; all logging goes to stderr.
- `DaemonClient`: wraps `java.net.http.HttpClient`; base URL from `GOVERSBX_DAEMON_URL` (default
  `http://daemon.internal:8787`); reads `$SANDBOX_NAME` once at startup and attaches it to every
  call.
- **Tool `request_network_access(host, reason, timeoutSeconds=45)`**: POSTs
  `/api/requests`, then GETs `/api/requests/{id}/await?timeoutSeconds=...` (bounded long-poll).
  Returns `{requestId, status: approved|denied|pending, host, message}`.
- **Tool `check_network_access_status(requestId)`**: plain `GET /api/requests/{id}`, no long-poll
  — the fallback path when the first tool times out while still pending.
- A companion `SKILL.md` (shipped via `sandbox-kit`, see §5) tells the agent when to reach for
  these tools, mirroring the style of the `## Network access` section already present in this
  environment's own CLAUDE.md for the existing reactive flow.

### 3. Daemon (`daemon` module)

- **HTTP layer: Javalin** (not raw `HttpServer`, not Spring Boot) — enough routing/JSON/long-poll
  support for ~6 endpoints without DI/annotation machinery.
- **Two Javalin instances in one JVM, sharing one `RequestStore`:**
  - `SandboxApiServer` (default port `8787`) — reachable from sandboxes; only request
    create/await/get endpoints. This is the port exposed at the always-allow-listed hostname.
  - `AdminServer` (default port `8788`) — **bound to `127.0.0.1` only**, never reachable from a
    sandbox; serves the approve/deny/list endpoints and the static web UI. This closes an
    otherwise-easy self-approval hole (a sandbox could not otherwise be prevented from calling
    "approve" on its own request, since full authn is explicitly out of MVP scope).
- **Persistence: in-memory only** (`ConcurrentHashMap` of `NetworkAccessRequest` +
  `ConcurrentHashMap` of `CompletableFuture` for long-poll wakeups). Acceptable because the
  durable state that matters (the actual network policy) lives in `sbx` itself, not the daemon.
- **Model** (`common`): `NetworkAccessRequest{id, sandboxName, host, reason, requestedAt, status,
  decidedAt, decidedBy, sbxApplied, sbxOutput}`, `enum RequestStatus{PENDING,APPROVED,DENIED}`.
- **Sandbox-facing API** (8787): `POST /api/requests`, `GET /api/requests/{id}`,
  `GET /api/requests/{id}/await?timeoutSeconds=`.
- **Admin API** (8788, loopback): `GET /api/requests?status=`, `POST /api/requests/{id}/approve`,
  `POST /api/requests/{id}/deny`, static UI at `/`, `GET /api/health`.
- **`SbxPolicyClient`**: on approve, `ProcessBuilder` runs
  `sbx policy allow network <host> --sandbox <sandboxName>` (binary path via
  `GOVERSBX_SBX_BINARY`, default `sbx`); captures exit code/stdout/stderr onto the record rather
  than failing the HTTP call — "approved" (human decision) and "policy actually applied"
  (mechanical effect) are reported as separate fields so the UI can surface a failed `sbx` call
  without hiding that the human did approve it.

### 4. Web UI

Server-rendered static `public/index.html` + `public/app.js` served by `AdminServer` — no SPA
build pipeline. Two sections (Pending / Decided), `fetch()` polling every ~2s, Approve/Deny
buttons POST to the admin endpoints. No auth (explicit scope boundary; safe only because the
admin server is loopback-bound).

### 5. Sandbox customization — best-effort, flagged as needing host-side verification

- **MCP registration**: a durable-startup command modeled on the observed `mcp-gateway` one:
  `claude mcp add goversbx-network "/opt/goversbx/bin/goversbx-mcp" --scope user || true`, run as
  `agent`, where the launcher script execs `java -jar goversbx-mcp-server.jar` with
  `GOVERSBX_DAEMON_URL`/`SANDBOX_NAME` fixed in its environment at launch (not inherited from the
  agent's interactive shell — verify this is actually how `--scope user` spawns the process before
  relying on it as a trust boundary).
- **Skill registration**: `sandbox-kit/skill/goversbx-network-access/SKILL.md`, same frontmatter
  shape as existing skills, destined for `~/.claude/skills/synced/<bucket>/...` — but *how a kit or
  skill bucket is authored on the host side so it lands in new sandboxes* is not visible from
  inside a sandbox and **must be confirmed against real `sbx` docs/source before M4.**
- The `sandbox-kit` Gradle task packages: the `mcp-server` shadow jar, the launcher script, the
  `SKILL.md`, and a draft durable-startup command JSON fragment — ready to hand to whatever the
  real host-side kit mechanism turns out to be.

### 6. End-to-end flow

Agent hits a block or consults the skill → calls `request_network_access` → mcp-server posts to
daemon (8787) → record appears `PENDING` → mcp-server long-polls `/await` → human sees it in the
web UI (8788, loopback) → clicks Approve → daemon runs `sbx policy allow network ... --sandbox
...`, records the outcome, completes the pending future → mcp-server's `/await` call returns
`approved` → agent retries its original request, now allowed. A timed-out `await` returns
`pending`; the agent later calls `check_network_access_status` to pick up the eventual decision.

### 7. Explicitly out of scope for this MVP

Revocation/expiry of approvals; re-request suppression for previously-denied hosts; authn/authz on
the daemon (mitigated only by loopback-binding the admin server); verifying a claimed
`sandboxName` actually corresponds to a live sandbox; multi-daemon HA; durable/crash-surviving
audit log; propagating a deny *reason* back to the agent; TLS between mcp-server and daemon.

### 8. Milestones

- **M1**: `common` + `RequestStore` + `SandboxApiServer` + bare `AdminServer` REST endpoints
  (no UI yet, `SbxPolicyClient` stubbed/logging only) — verify the create→approve→unblock loop
  via `curl`.
- **M2**: add the static web UI to `AdminServer`, replacing manual curl approval.
- **M3**: build `mcp-server` against the MCP Java SDK (stdio, two tools, `DaemonClient`), validate
  by manually `claude mcp add`-ing it locally (not yet inside a real sandbox) against a
  locally-run daemon.
- **M4**: build the `sandbox-kit` packaging task; **resolve the host-side kit/skill-authoring
  open question against real `sbx` docs/source before going further** — this is a hard checkpoint,
  not a soft note.
- **M5**: wire the real `sbx policy allow` subprocess call, deploy the M4 kit into an actual
  sandbox, run the full flow from a live sandbox end to end, confirm the proxy unblocks the host
  afterward.

## Critical files to create

- `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`
- `common/src/main/java/run/fabian/common/NetworkAccessRequest.java` (+ `RequestStatus`)
- `daemon/src/main/java/run/fabian/daemon/Main.java`,
  `daemon/.../store/RequestStore.java`, `daemon/.../http/SandboxApiServer.java`,
  `daemon/.../http/AdminServer.java`, `daemon/.../sbx/SbxPolicyClient.java`
- `daemon/src/main/resources/public/index.html`, `public/app.js`
- `mcp-server/src/main/java/run/fabian/mcpserver/Main.java`,
  `mcp-server/.../DaemonClient.java`,
  `mcp-server/.../tools/RequestNetworkAccessTool.java`,
  `mcp-server/.../tools/CheckNetworkAccessStatusTool.java`
- `sandbox-kit/skill/goversbx-network-access/SKILL.md`
- `sandbox-kit/startup/000-cmd.sh` (draft, mirrors observed durable-startup shape)

## Open questions carried forward (not blocking M1–M3)

1. How `daemon.internal` (or chosen hostname) resolves, and what address
   `SandboxApiServer` must bind to for "always allow-listed" to actually work globally.
2. Host-side mechanism to author a new kit/skill bucket so it's baked into new sandboxes — hard
   checkpoint before M4.
3. Whether the daemon should independently verify `sandboxName` against a live `sbx` inventory
   rather than trusting the value the mcp-server sends.
4. Pin an exact MCP Java SDK version before M3 and verify builder/tool-spec class names against
   its javadoc (pre-1.0, API has churned).
5. Whether `claude mcp add --scope user` gives the spawned stdio process a fixed environment or
   lets it inherit the agent's shell env — affects whether `$SANDBOX_NAME` is tamper-resistant.

## Verification

- M1: `./gradlew :daemon:run`, then `curl -X POST localhost:8787/api/requests -d '{...}'`,
  `curl localhost:8787/api/requests/{id}/await`, `curl -X POST localhost:8788/api/requests/{id}/approve`
  from another terminal while the await call is blocked — confirm it unblocks with `approved` and
  the stub `SbxPolicyClient` logged the command it *would* run.
- M2: same flow, but approve via clicking in the browser at `localhost:8788`.
- M3: run `./gradlew :mcp-server:shadowJar`, `claude mcp add goversbx-network <jar launch cmd>
  --scope user` in a throwaway local Claude Code session, call `request_network_access` from a
  prompt, confirm it reaches the locally-running daemon and the tool call resolves correctly.
- M5: from inside a real `sbx` sandbox carrying the M4 kit, trigger a blocked request, call the
  tool, approve via the web UI, confirm the originally-blocked request now succeeds and
  `sbx policy ls` shows the new allow rule for that sandbox.

## Status (post-M4)

M1–M4 are implemented and were manually verified:
- M1/M2: curl-driven create → pending → long-poll → approve/deny → unblock flow, including the
  409-on-redecision and port-isolation checks, against the real `daemon` jar.
- M3: a real MCP JSON-RPC stdio session (initialize → tools/list → tools/call →
  check_network_access_status) driven against the real `mcp-server` jar and a running daemon.
- M4: `sandbox-kit/build/distributions/goversbx-sandbox-kit-*.zip` is produced by
  `./gradlew :sandbox-kit:packageKit` and contains the mcp-server jar, launcher, `SKILL.md`, and
  the draft startup command — still unverified against real `sbx` host-side kit docs, as flagged
  above.

M5 (real `sbx policy allow` call against a live sandbox, resolving open question 2) is not done.

Pinned MCP Java SDK version: `2.0.1` (resolves open question 4). Its API is split across
`mcp-core` + `mcp-json-jackson3`, pulled transitively via the `io.modelcontextprotocol.sdk:mcp`
aggregator artifact; `mcp-server/src/main/java/run/fabian/mcpserver/Main.java` constructs the
`McpJsonMapper` explicitly via `JacksonMcpJsonMapperSupplier` rather than relying on an SDK-wide
default (none was found on this SDK version).
