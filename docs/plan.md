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
`sandboxName` actually corresponds to the sandbox that sent it (known risk, see open question 3);
multi-daemon HA; durable/crash-surviving audit log; propagating a deny *reason* back to the agent;
TLS between mcp-server and daemon.

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
3. **Known risk, accepted for the MVP: sandbox identity is not verified.** *(RESOLVED by the
   second 2026-10-14 revision below, via `sbx secret set-custom` - conditional on an operator
   running the registration step before a sandbox starts. Left here verbatim as the historical
   record of the risk as first identified.)* The daemon trusts
   whatever `sandboxName` the mcp-server sends with a request - there is no independent check
   against a live `sbx` inventory. This is not just a stdio-transport quirk (open question 5) -
   the same problem would resurface under an HTTP-transport design (see below), because in either
   case identity is established by *something baked into the sandbox at kit-install time*
   (an env var, or a static bearer header), and anything living inside the sandbox's own
   filesystem/config is something the agent could in principle read, copy, or tamper with. The
   robust fix is for identity to be established by the network/proxy layer instead - the same
   sandbox-to-daemon connection should carry proof of origin that the proxy attaches and the
   sandbox cannot forge, mirroring how the already-registered `mcp-gateway` server's
   `"Authorization": "Bearer proxy-managed"` header looks like a placeholder the sbx egress proxy
   rewrites per-request with a real, per-sandbox credential as the request leaves the sandbox
   (the same proxy already injects GitHub credentials this way for git operations - see this
   environment's own "Git Authentication" docs). Whether the sbx proxy can do this for an
   arbitrary self-hosted service like ours is unconfirmed and would need to be checked against
   real `sbx` docs/source, same as open question 2. Until then: a sandbox could falsely claim
   another sandbox's name and get that sandbox's host approved instead of its own. Low severity
   for now since approvals are already visible to a human in the UI (sandbox name included), but
   worth fixing before this goes beyond a single trusted operator's own use.
4. Pin an exact MCP Java SDK version before M3 and verify builder/tool-spec class names against
   its javadoc (pre-1.0, API has churned).
5. *(Superseded by the 2026-10-14 revision - there is no more spawned stdio process at all.)*
   ~~Whether `claude mcp add --scope user` gives the spawned stdio process a fixed environment or
   lets it inherit the agent's shell env — affects whether `$SANDBOX_NAME` is tamper-resistant.~~
   The question moved: see the revision section for the new HTTP-header equivalent of this same
   risk.
6. *(Acted on by the 2026-10-14 revision below.)* Whether `mcp-server` should even be a process
   that runs inside the sandbox at all. The precedent we found (`mcp-gateway`) is registered via
   `--transport http` against an internal hostname, not spawned locally via stdio - i.e. the real
   MCP server for that tool does not run inside the container. Moving `goversbx-network` to the
   same shape was not pursued immediately when this question was first raised, but was done in the
   following revision once the user asked for it explicitly.

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
aggregator artifact; `McpContext` (see revision below) constructs the `McpJsonMapper` explicitly
via `JacksonMcpJsonMapperSupplier` rather than relying on an SDK-wide default (none was found on
this SDK version).

## Revision (2026-10-14): merge the MCP server into the daemon

Prompted by the user asking, essentially, "does the MCP server actually need to run inside the
container?" - it doesn't, and open question 6 above had already flagged why: the real precedent
(`mcp-gateway`) is registered via `--transport http` against an internal hostname, not spawned
locally. This revision acts on that: `mcp-server` and `common` are gone as separate Gradle modules;
everything lives in `daemon` now, split into two **bounded contexts**
(`run.fabian.daemon.networkaccess` and `run.fabian.daemon.mcp`) that only talk to each other
through one published `networkaccess.api` package, each internally structured along
domain → application/ports → adapters lines. See `AGENTS.md` for the convention in detail.

What changed mechanically:
- `mcp-server`'s stdio transport is replaced by `io.modelcontextprotocol.server.transport
  .HttpServletStreamableServerTransportProvider`, mounted directly onto the sandbox-facing
  Javalin instance's Jetty server (`config.jetty.modifyServletContextHandler(...)`) at `/mcp` -
  confirmed working via `javap` against the real jars before writing any code, and now covered by
  an automated integration test (`McpEndpointIntegrationTest`) that drives it with the MCP Java
  SDK's own sync client.
- The old sandbox-facing REST API (`/api/requests` on port 8787) is deleted entirely - nothing
  calls it anymore, since the `mcp` bounded context now calls `networkaccess.api.NetworkAccessApi`
  as a plain in-process Java method call instead of over HTTP. `DaemonClient` (the old HTTP client
  side of that call) is gone, and so is the client/server timeout-duplication concern it used to
  need bounded-long-poll logic to work around.
- `sandbox-kit` shrinks to just `SKILL.md` + a durable-startup draft; no jar, no launcher script.
  The draft now registers via
  `claude mcp add goversbx-network "http://daemon.internal:8787/mcp" --transport http --header "X-Goversbx-Sandbox-Name: $SANDBOX_NAME" --scope user`.

**Open question 3 (sandbox identity), new shape - still not fixed, as intentionally decided by the
user ("als bekanntes Risiko dokumentieren"):** there is no more per-process `$SANDBOX_NAME` env var
now that one daemon process serves every sandbox's MCP calls, so identity now travels as the
`X-Goversbx-Sandbox-Name` HTTP header instead, read via a
`McpTransportContextExtractor<HttpServletRequest>` into `McpSyncServerExchange.transportContext()`.
This is exactly as trustworthy as the old env-var approach - a static value baked in once by the
kit's startup script, not independently verified by the daemon - confirmed by design, not an
oversight: moving to HTTP transport does not by itself change who can forge the value, only where
it travels. The robust fix (identity attached by the network/proxy layer, unforgeable by the
sandbox itself) remains unimplemented and would need the same host-side `sbx` verification as
before.

**A stronger fix was discussed but deliberately not implemented this round**: inject a
per-sandbox secret via the already-documented `sbx secret set <name> --sandbox <sandbox> -t
<token>` mechanism (confirmed real, unlike the proxy-header-rewriting guess from the original open
question 3) at sandbox-registration time, from an operator-triggered daemon endpoint - the daemon
would then look up `sandboxName` from the verified token rather than trusting a client-supplied
value at all. This is a materially better foundation than the current header (the token is minted
and injected from *outside* the sandbox, by whoever controls `sbx`, rather than baked in by a
script the sandbox can in principle inspect) but needs a new registration step and confirmation of
how an `sbx secret set` value actually surfaces inside a sandbox process. Deliberately deferred -
the user asked to document this as a known risk for now, not fix it.

Verification performed for this revision: `./gradlew :daemon:test` (20 tests: domain invariants,
application-service behavior including the approve-while-blocked race, admin REST API via
`javalin-testtools`, and the real MCP HTTP endpoint via the MCP Java SDK's own client) plus a
manual end-to-end curl session driving the full MCP Streamable HTTP handshake (`initialize` →
`notifications/initialized` → `tools/call`) with a custom `X-Goversbx-Sandbox-Name` header,
confirming it arrives correctly in the admin API's view of the resulting request.

## Revision (2026-10-14, continued): `sbx secret set-custom` resolves open question 3 for real

The "stronger fix" sketched in the previous revision turned out to already exist as a real,
public, documented `sbx` feature - **`sbx` is open source** (`docker/sbx-releases`,
`docker/sbx-kits-contrib` on GitHub; docs at docs.docker.com/ai/sandboxes/), which is how this was
found and confirmed rather than guessed:

- `sbx secret set-custom --sandbox <name> --target <host> --env <VAR> --placeholder <p> --value <v>`
  is a stable (GA since sbx v0.32.0) command. The sandbox only ever sees `<p>` (via the declared
  `--env` var); the sbx proxy does a **string replacement across every outbound HTTP header** sent
  to `<host>`, rewriting `<p>` to `<v>` before the request leaves the sandbox - confirmed to work
  for arbitrary custom headers, not just `Authorization`, and confirmed scopable per-sandbox via
  `--sandbox` (vs. `-g` for global). This is exactly the `GH_TOKEN`/`MCP_SENTINEL_TOKEN_NAME`
  pattern already observed empirically in this very sandbox's own env vars (see the first
  revision's recon) - now known to be a generic, documented mechanism, not something special-cased
  to GitHub/mcp-gateway. (One empirical check *did* rule something out first: a plain request
  through this sandbox's general egress proxy to `httpbin.org/headers`, with no secret registered
  for that target, came back with no sandbox-identifying header at all - confirming the mechanism
  requires an explicit `--target`-scoped registration, it is not automatic for arbitrary egress.)
- This daemon (not the sandbox) can run this command itself, exactly like it already shells out to
  `sbx policy allow` - a new `sandboxidentity` bounded context does this:
  `sandboxidentity.application.SandboxIdentityService` → `application.port.SbxCustomSecretPort` →
  `adapter.sbx.SbxCliCustomSecretAdapter` (same dry-run-by-default shape as `networkaccess`'s sbx
  adapter), fronted by an admin-only (loopback) `adapter.web.SandboxIdentityController` at
  `POST /api/sandboxes/{name}/register`. The registered `--value` is simply the sandbox's own name
  - the property needed is unforgeability, not secrecy, so there was no reason to mint a separate
  opaque token.
- `sandbox-kit/startup/000-cmd.sh` now sources the `X-Goversbx-Sandbox-Name` header from
  `$GOVERSBX_SANDBOX_TOKEN` (the *placeholder*, populated by the `--env` declaration) instead of
  `$SANDBOX_NAME` (a plain, self-reported value). By the time this header reaches the daemon, the
  sbx proxy has already rewritten the placeholder to the real sandbox name - the daemon doesn't
  need to change anything about how it reads the header, only sandboxes gain the one extra step of
  carrying a proxy-substituted value instead of a self-reported one.

**This genuinely resolves open question 3**, conditional on one operational step that still has to
happen outside this repo: an operator (or future provisioning automation) must call
`POST /api/sandboxes/<name>/register` (or run `sbx secret set-custom` directly) for a sandbox
*before* that sandbox's kit-install step runs, since `$GOVERSBX_SANDBOX_TOKEN` only exists once
that registration has happened - this ordering requirement is not yet automated end-to-end, and
doing so would need to be wired into however sandboxes actually get created (still part of open
question 2, the host-side kit-authoring mechanism). **This ordering requirement itself is removed
by the next revision below** - left here as the historical record of what this revision actually
shipped, not as current guidance. One other real `sbx` caveat worth watching,
found in the same research: a separate GitHub issue documented that the placeholder can get
base64-encoded by some HTTP clients before reaching the proxy, breaking substitution for `Basic`
auth specifically - does not apply to our plain custom header, but worth re-checking if this
header is ever base64-wrapped for any reason later.

Verification performed: `./gradlew :daemon:test` (23 tests total, 3 new for `sandboxidentity`: a
unit test asserting the service calls the port with the sandbox's own name as the substituted
value, one asserting port failures surface correctly, and a `javalin-testtools` integration test
for the registration endpoint) plus a manual dry-run call against the running daemon
(`POST /api/sandboxes/claude-goversbx/register`) confirming the exact `sbx secret set-custom`
command that would be run.

Sources consulted: [`sbx secret set-custom`](https://docs.docker.com/reference/cli/sbx/secret/set-custom/),
[Manage credentials](https://docs.docker.com/ai/sandboxes/configuration/credentials/),
[Support custom secrets / environment variables passed into sandboxes (docker/sbx-releases#7)](https://github.com/docker/sbx-releases/issues/7),
[docker/sbx-kits-contrib `claude` kit](https://github.com/docker/sbx-kits-contrib/tree/main/claude).

## Revision (2026-10-14, continued again): reactive registration - no pre-sandbox-start step needed

The user pushed back on the "register before kit-install" ordering requirement as inconvenient and
asked whether the daemon could instead poll `sbx` and register new sandboxes automatically. Two
things, checked rather than assumed, make a better design than periodic polling possible:

- **`sbx` is not actually open source in the sense of publishing application code** - checked by
  listing `docker/sbx-releases`' repo contents directly via `gh api`: it contains only `LICENSE`,
  `README.md`, `SECURITY.md` (25 KB total), no source. "Open source" apparently meant the release
  notes/issue tracker are public, not the CLI/daemon implementation - worth remembering before
  citing "the source" for this project again.
- **Secret registration already applies to already-running sandboxes without a restart** -
  confirmed via `gh api repos/docker/sbx-releases/releases` directly (not just a search-engine
  summary): "Adding, updating, or removing global service secrets now updates existing local
  sandboxes without a restart... Sandbox-scoped command and reference secrets also take effect
  immediately." Combined with bulk secret removal also being described as affecting running
  sandboxes elsewhere in the same release history, there's no indication the simple static
  `--value` case is excluded from this live-update behavior.
- **`sbx ls --format json`** (real output shape confirmed by the user pasting a genuine excerpt,
  not guessed) lists every sandbox as a flat JSON array (not wrapped in `{"sandboxes": [...]}`,
  per sbx's own v0.48.0 changelog) with snake_case fields: `name`, `id`, `agent`, `status`,
  `last_used_at`, `workspaces` (array), `created_at`.

This enables a strictly better design than both "register before start" and "poll periodically":
**reactive, on-demand registration, triggered by the daemon itself seeing the problem.**

- `sandbox-kit/startup/000-cmd.sh`'s `--header` value is now a **hardcoded literal**
  (`goversbx-sandbox-identity-placeholder-v1`), not an env-var lookup. This matters because
  `claude mcp add --header` resolves its value once, at registration time, and freezes it into
  `~/.claude.json` - if it read a per-sandbox env var that didn't exist yet (because registration
  hadn't happened), that would be baked in as empty forever, regardless of any later registration.
  A hardcoded literal sidesteps this entirely: the daemon-side substitution can happen at any time,
  live, independent of when this script ran.
- `sandboxidentity` gains a published `api` package (`SandboxIdentityApi`) and a new driven port
  (`SbxSandboxListPort`, implemented by `adapter.sbx.SbxCliSandboxListAdapter` around `sbx ls`).
  `SandboxIdentityService.isUnsubstitutedPlaceholder(headerValue)` checks whether an incoming
  request's sandbox-name header still equals the raw placeholder (meaning: not substituted yet,
  meaning: this sandbox isn't registered). `registerAnyUnregisteredSandboxes()` then calls `sbx ls`
  and registers every running sandbox this daemon-process doesn't yet have in its own in-memory
  `registered` set (a failed registration is *not* added to that set, so it's retried next time).
- `mcp.tool.RequestNetworkAccessTool` now takes a `SandboxIdentityApi` too (making `mcp` depend on
  a second bounded context's published API, same rule as before - never `sandboxidentity.domain`/
  `.application`/`.adapter` directly). Before doing anything else, it checks
  `isUnsubstitutedPlaceholder(sandboxName)`; if true, it calls
  `registerAnyUnregisteredSandboxes()` and returns a message asking the agent to retry - no
  `NetworkAccessRequest` is created for that call. `check_network_access_status` doesn't need this
  check; it never used `sandboxName` in the first place.
- The proactive `POST /api/sandboxes/{name}/register` endpoint stays - it's a useful way for an
  operator to pre-warm a sandbox and skip the one-retry round trip, but is no longer required for
  correctness.

A brand-new sandbox's very first `request_network_access` call will therefore always get a
"registering now, please retry" response once, then succeed normally - including catching up any
*other* sandboxes that started around the same time, since `sbx ls` returns all of them. The
`SKILL.md` shipped in `sandbox-kit` was updated to tell the agent this is expected, not an error.

**`sbx ls`'s JSON flag spelling is version-sensitive** (`--json` pre-v0.48.0, `--format json` from
v0.48.0 on, per the same release notes) - `SbxCliSandboxListAdapter` takes the exact args as a
configurable list (`GOVERSBX_SBX_LS_FORMAT_ARGS`, default `--format json`) rather than hardcoding
one spelling, for exactly this reason.

Verification performed: `./gradlew :daemon:test` (29 tests total; new ones cover
`isUnsubstitutedPlaceholder`, bulk registration including the not-re-registering-already-registered
and retry-after-failure cases, JSON parsing of a realistic `sbx ls` fixture built from the user's
real pasted output, and an MCP-level test asserting the retry message and that no network-access
request gets created) plus a manual end-to-end curl session simulating an unregistered sandbox's
first call and confirming the exact retry message, with the expected (and gracefully handled)
`sbx: No such file or directory` error logged since no real `sbx` binary exists in this dev
environment.

Sources consulted (via `gh api`, not search-engine summaries, after confirming `api.github.com`
access): [`docker/sbx-releases` releases](https://github.com/docker/sbx-releases/releases)
(specifically the entries documenting live secret updates and the `sbx ls` v0.48.0 JSON shape
change), plus the user's own pasted `sbx ls --format json` output for the real field names.

## Revision (2026-10-14): support agents other than Claude Code - pi first

The daemon was already agent-agnostic: anything that speaks MCP over Streamable HTTP and sends the
placeholder header works, and the sbx proxy's substitution happens at the network layer regardless
of which process sent the request. Only `sandbox-kit` was Claude-specific (`claude mcp add`).

- `sandbox-kit` is restructured into a shared `skill/` plus `agents/<agent>/{startup.sh,
  manifest-fragment.json}`. The old `startup/000-cmd.sh` is now `agents/claude/startup.sh`,
  unchanged in behavior. `packageKit` bundles `skill/` and `agents/`.
- `agents/pi/startup.sh`: pi supports MCP in core since 1.0 (2026-10-01). Facts taken from pi's own
  `packages/coding-agent/docs/mcp.md` (fetched via `gh api`): user-level servers live in
  `~/.pi/agent/mcp.json` under `mcpServers`, HTTP servers take `url` + `headers`; header values
  interpolate `${VAR}` and run `!command`; the docs don't mention a `--header` flag for
  `pi mcp add` (pi 1.2.0 turned out to have one - see "Verified against real pi" below); a project's
  `.pi/mcp.json` is only read once the project is trusted; tools default to `codemode` exposure
  (callable from scripts, not declared to the model), `direct` declares them like built-ins; pi
  also scans `~/.agents/skills/` (from pi's `docs/skills.md`). The script therefore merges a
  `goversbx-network` entry with `"exposure": "direct"` and the hardcoded placeholder into the
  user-level file using Node (part of the pi image), keeping other entries and failing on a
  malformed file instead of overwriting it, then copies the shared skill to
  `~/.agents/skills/`.
- `SKILL.md` now says tools may appear with a server prefix (pi names them
  `mcp__goversbx-network__<tool>`) instead of assuming bare names.

Verification: the pi script was run against a fresh home, an existing config with another server
(preserved, and idempotent on a second run), and a malformed config (exit 1, file untouched).

**Verified against real pi** (`@earendil-works/pi-coding-agent@1.2.0`, installed into a scratch
prefix once `registry.npmjs.org` was approved), with the daemon running locally:

- `pi mcp list` on the generated config: `goversbx-network: connected, 2 tools (direct, global)`,
  tools `request_network_access` and `check_network_access_status`, no errors.
- pi sends the header verbatim: pointed at a header-logging listener, the initialize POST carried
  `X-Goversbx-Sandbox-Name: goversbx-sandbox-identity-placeholder-v1`.
- `pi mcp add goversbx-network --url ... --header X-Goversbx-Sandbox-Name=... --exposure direct
  --description ...` writes a byte-identical entry, so the script's format is exactly pi's own. The
  script still edits the JSON directly so it doesn't need pi on `PATH` or depend on CLI flags the
  docs don't mention.
- Skill discovery: pi's `DefaultResourceLoader` (what sessions use) finds
  `goversbx-network-access` in `~/.agents/skills/` with no diagnostics. pi's exported
  `loadSkills()` alone does *not* - it only scans `~/.pi/agent/skills`; `~/.agents/skills` is added
  by the package manager (`dist/core/package-manager.js`), unconditionally and without trust gating
  for the user-level directory.

Not verified: an actual tool call from a pi session (needs model credentials).

Sources: [earendil-works/pi `docs/mcp.md`](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/mcp.md),
pi `docs/skills.md`, [docker/sbx-kits-contrib `pi` kit](https://github.com/docker/sbx-kits-contrib/tree/main/pi).

### Follow-up: oh-my-pi (omp)

`agents/omp/startup.sh`, based on omp's own `docs/mcp-config.md`, `docs/skills.md`,
`src/discovery/builtin.ts` and `packages/coding-agent/package.json` (all via `gh api`):

- User MCP config is `<agentDir>/mcp.json`, user skills `<agentDir>/skills/<name>/SKILL.md`.
  `<agentDir>` is profile-scoped: `~/.omp/agent` by default, `~/.omp/profiles/<name>/agent` under
  `OMP_PROFILE`/`PI_PROFILE` (and then the default profile's files are *not* read);
  `PI_CODING_AGENT_DIR` overrides the default-profile dir. The script follows these rules, with
  `OMP_AGENT_DIR` as an explicit override; `PI_CONFIG_DIR` is not handled.
- Remote servers need `"type": "http"` + `url` + `headers`. Header values: leading `!` runs a
  command, a value that exactly names a set env var is replaced by it, `${VAR}` is expanded. The
  placeholder literal is none of these, so it's sent verbatim.
- omp's `~/.agents/skills` provider is opt-in for user skills, so the skill goes into omp's native
  skills dir (loaded by default; requires a `description`, which ours has).
- MCP tools are named `mcp__<server>_<tool>` after sanitizing (`mcp__goversbx_network_<tool>`);
  the skill already avoids assuming exact names.
- omp is a Bun app (`"engines": {"bun": ...}`), so Node may be absent: the merge runs with `node`
  if present, else `bun`, else fails without writing anything.

Verification: fresh home, existing config (other servers and top-level keys like
`disabledServers` preserved, idempotent), named profile (written to the profile's dir only),
malformed config (exit 1, untouched), and no JS runtime on `PATH` (exit 1, nothing created). Not
run against a real omp installation, and the `bun -e` path is untested (no Bun here).

## Revision (2026-10-14): first real e2e run, `--host` not `--target`

`scripts/e2e-claude.sh` (added for this purpose) ran on the user's macOS host for the first time.
Confirmed against real `sbx`: `sbx create --name N claude DIR`, `sbx exec`, `sbx policy allow
network localhost:8787 --sandbox N` (sandbox then reaches the daemon at `host.docker.internal:8787`),
the sandbox's `HTTP(S)_PROXY` pointing at `gateway.docker.internal:3128`, and `sbx ls --format json`.

Failed: `sbx secret set-custom` rejected `--target` (`error: unknown flag: --target`). The earlier
revisions above took `--target` from docker/sbx-releases#7, an example from when the feature was
experimental; the current flag is `--host` (repeatable, supports `*`/`**` wildcards), confirmed by a
real invocation in docker/sbx-releases#650. Fixed in `SbxCliCustomSecretAdapter`; references to
`--target` in the earlier revision sections above are historical and wrong.

The run also showed `registerAnyUnregisteredSandboxes()` registering every running sandbox on the
host, including unrelated ones (they all failed on the flag, so nothing changed). New
`GOVERSBX_IDENTITY_SANDBOX_PATTERN` (regex, default `.*`) limits that; the e2e script restricts it
to its own sandboxes.

New risk found in the same research: docker/sbx-releases#230 describes custom secrets as keyed by
placeholder, and goversbx deliberately uses one placeholder for every sandbox. Whether `--sandbox`
scoping keeps per-sandbox values apart is not documented; the e2e script now creates a second
sandbox and checks that each sandbox's request arrives under its own name (step 4b). Fallback if it
doesn't: per-sandbox placeholders derived from the sandbox name (see `docs/status.md`).

## Revision (2026-10-14): substitution needs `--host localhost`

The second real e2e run registered secrets successfully (with `--host host.docker.internal`) but the
daemon still received the raw placeholder. `scripts/diagnose-substitution.sh` (header-echo server on
the host, one sandbox-scoped secret per `--host` variant) settled it on sbx v0.48.0: for traffic from
a sandbox to `host.docker.internal:<port>`, only `--host localhost` (and the catch-all `**`) are
substituted - consistent with the network rule for that path also having to be `localhost:<port>`.
`--host` rejects ports; custom header names are substituted too; HTTPS works. The daemon's
`GOVERSBX_IDENTITY_TARGET_HOST` now defaults to `localhost`, and the kit scripts' daemon host default
changed from the fictional `daemon.internal` to `host.docker.internal`.

Still unexplained: in that run, Claude's `claude mcp list` reported "tools fetch failed - Error
POSTing to endpoint: EOF" once secrets existed; plain curl/Node POSTs through the proxy with such a
secret present did not reproduce it.

## Revision (2026-10-14): MVP verified end to end

Third real run of `scripts/e2e-claude.sh` (sbx v0.48.0, user's host): reactive registration with
`--host localhost`, proxy substitution of the shared placeholder (real sandbox name arrives),
approval -> `sbx policy allow network <host> --sandbox <n>` -> host reachable, identity isolation
between two sandboxes sharing the placeholder, and the full path with real Claude Code (`claude -p`)
all passed. The only failures were the script's `sbx policy ls | grep` check, which can't work against
v0.48's summary output; fixed to use `--format json` and treat reachability as the authority. See
`docs/status.md` for the current state and next steps.

## Revision (2026-10-14): sandbox-kit becomes real sbx kits

Resolves open question 2 / the M4 kit mechanism on paper, based on the kit spec in
`docker/sbx-kits-contrib` (`spec/SPEC-v2.md`, the `kit-author` skill topics, and the `claude` kit's
own `spec.yaml` as the reference for MCP registration):

- `sandbox-kit/agents/<agent>/{startup.sh,manifest-fragment.json}` (drafts modeled on
  `/etc/durable-startup.d`) are replaced by `sandbox-kit/kits/goversbx-{claude,pi,omp}/`, each a
  `schemaVersion: "2"`, `kind: mixin` kit applied with `sbx create|run --kit` or `sbx kit add`.
  The manifest fragments are gone: sbx generates the durable-startup entries from `setup.startup`.
- Claude registers at `setup.install` time as `agent` (race-free, like the `claude` kit's
  `mcp-gateway`), with an add-only `setup.startup` fallback; pi/omp keep their merge scripts,
  shipped under `files/home/.local/share/goversbx/` and run from `setup.startup`.
- The network rule for the daemon moves into the kits (`permissions.network.allow`:
  `localhost:<port>` and `<daemonHost>:<port>`), parameterized by kit args `daemonHost`/`daemonPort`.
  The e2e script no longer adds it by hand.
- Skills ship via `files/home/`. Kits must be self-contained, so each carries a copy of the shared
  skill, kept in sync by Gradle (`syncKitSkills`, `verifyKitSkills` in `check`).
- A kit cannot start the daemon: kit hooks run inside the sandbox, the daemon needs the host's
  `sbx` CLI and must stay outside the sandbox's control.

Not yet run against a real sbx host; see `docs/status.md` for the assumptions the next e2e run checks.

## Revision (2026-10-14): Claude kit verified on the host

Fourth real run of `scripts/e2e-claude.sh`, now creating both sandboxes with
`sbx create --kit sandbox-kit/kits/goversbx-claude`: all checks passed. Confirmed: `sbx kit validate`
accepts the mixin, it composes with the built-in `claude` agent (our install command runs after the
claude kit's), the kit's `permissions.network.allow` alone makes the daemon reachable, and the
install-time `claude mcp add` plus the `files/home/` skill are in place before Claude runs. Open
question 2 is closed. The mixin's agent-instructions file was not found in the sandbox (harmless).

## Revision (2026-10-14): host launcher `scripts/goversbx`

A kit can't start the daemon (it runs inside the sandbox), so `scripts/goversbx` does it on the
host: `run` ensures a healthy daemon (building the jar if stale, starting it in the background in
real mode), then execs `sbx run --kit sandbox-kit/kits/goversbx-<kit> ...`; `start|stop|status|logs`
manage it. The daemon deliberately outlives the session because it serves every sandbox. For an
existing sandbox `--kit` is left out (`sbx kit add` covers that case).

## Revision (2026-10-14): make Claude pick goversbx without being told

First interactive use via `scripts/goversbx run claude ...`: on a blocked `curl`, Claude followed the
sbx-provided, always-loaded `CLAUDE.md` section "When a request is blocked" (ask the user to run
`sbx policy approval ...`) and loaded the skill only after "use goversbx". Skill descriptions and a
mixin's progressive-disclosure `agentInstructions` lose against always-loaded project memory. The
Claude kit therefore writes a marker-wrapped rule into `~/.claude/CLAUDE.md` (user memory) at install
time that explicitly supersedes that section for approvable blocks (not for org-policy or local deny
rules), and the skill's description now names the proxy's 403 messages. The e2e test's Claude step
switched to a prompt that doesn't mention goversbx.

## Revision (2026-10-14): registrations keyed by sandbox id

`SandboxIdentityService` remembered registered sandboxes by name for the daemon's lifetime. With the
long-running daemon from `scripts/goversbx`, a sandbox removed and recreated under the same name lost
its sandbox-scoped secret but was never registered again - the agent got "please retry" forever.
`SbxSandboxListPort` now returns `RunningSandbox(name, id)` from `sbx ls --format json`, and the
service keys registrations by both. The manual admin `register(name)` no longer marks anything (it
doesn't know the incarnation); at worst the next reactive pass registers once more.

## Revision (2026-10-14): browser notifications

Wanted: be told about new requests and answer them from the notification. Options considered:
browser notifications from the admin UI, native macOS notifications with buttons (e.g. `alerter`),
and phone channels (a Telegram bot works outbound-only; ntfy/Pushover buttons would need the admin
API reachable from outside). Built first: browser notifications, since they need no dependency and
the user runs Firefox (notification `actions` since Firefox 152, per MDN compat data).
`networkaccess.api.NetworkAccessListener` + `NetworkAccessService.subscribe()` publish
created/decided events; `AdminEventStream` serves them as SSE on the loopback admin port; the UI
replaces 2 s polling with the stream (15 s fallback poll) and lets a service worker own the
notifications, tagged by request id. Other channels can subscribe to the same listener later, in a
`notification` context of their own.

## Revision (2026-10-15): sandbox identity uses a secret token, not the name (threat-model F1)

A security review (`docs/threat-model.md`, threat T1) found the identity header forgeable, confirmed
live against the running daemon: because the registered `set-custom --value` was the sandbox's own
*name*, the post-substitution header the daemon sees for an honest sandbox is byte-identical to one a
rogue agent supplies by hand (names are public, and a sandbox can POST any header straight to
`:8787/mcp`). The "unforgeability, not secrecy" argument in the earlier `set-custom` revisions above
was wrong - that is the historical record of the mistake, left verbatim.

Fixed: the daemon now mints a random per-sandbox secret token (`gvsbx-<base64url>`) as the `--value`,
keeps a private token->name map, and resolves the incoming header through it
(`SandboxIdentityApi.resolveSandboxName`, replacing `isUnsubstitutedPlaceholder`). Any value that
doesn't resolve - the placeholder, a stale token, or a forged value - triggers registration and a
retry and creates no request. A sandbox only ever holds the placeholder; the proxy substitutes it to
that sandbox's own token, so a forger can only ever resolve to itself or be rejected.

No persistence needed across a daemon restart: the token in sbx outlives the daemon, the in-memory
map does not, so a stale token simply doesn't resolve and self-heals via one re-registration (mints a
fresh token, overwrites the sbx secret live) on the next retry - the same single "please retry" a new
sandbox already does. Residue tracked under threat-model T2: the token map grows slowly (old tokens
never evicted, each still maps only to its own sandbox), and an unresolvable value costs one `sbx ls`.

The black-box contract suite learns each sandbox's token the way the proxy would
(`DaemonProcess.asSandbox`/`tokenFor`, reading the `set-custom --value` from `FakeSbx` or the dry-run
output). `./gradlew check` + `:daemon:binaryTestJar` pass; verified live that a forged header is now
rejected with no request created. Not yet re-run end to end against a real sbx host.

## Revision (2026-10-15): host-input validation (F2) and admin-port guard; F3 confirmed

Continuing the security review (`docs/threat-model.adoc`).

F2 (host was passed to `sbx policy allow` unvalidated): added
`networkaccess.domain.HostValidation`, the single authority for what a requestable host is - a
well-formed DNS hostname with an optional numeric port, rejecting wildcards, URLs/paths, whitespace,
malformed labels, bad ports and all IP literals (an IP hides from the approver what is allowed and is
the usual route to cloud metadata / internal ranges). Enforced as a domain invariant in
`NetworkAccessService.createRequest` (throws `InvalidHostException`) and surfaced to the MCP tool
through a new published `NetworkAccessApi.hostRejectionReason` (the `mcp` context must not import
`networkaccess.domain`). Not covered: a valid hostname that resolves to a private/loopback IP - that
is sbx/proxy enforcement, not input validation.

F3 guard: `RequestNetworkAccessTool.targetRejectionReason` refuses a request that targets the
daemon's own admin port on a host-local alias (or such an alias with no port). The admin port is
threaded from `Main` through `McpContext.build`. This closes the path where a sandbox gets the admin
port opened to itself *through goversbx*.

F3 itself is now CONFIRMED on the host (`scripts/diagnose-admin-reachability.sh`): with
`localhost:8788` allowed for a sandbox, `curl host.docker.internal:8788/api/health` from inside it
returns 200 - the loopback bind is not a boundary, the proxy re-originates to host loopback. Severity
Critical. The real fix (authenticate the admin API with a startup token the sandbox never sees) is the
next step and is not in this pass. `./gradlew check` + `:daemon:binaryTestJar` pass.
