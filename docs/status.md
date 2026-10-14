# Implementation status

Snapshot as of 2026-10-14. This is the place to resume from. `docs/plan.md` has the full history
and rationale (dated revision sections); `AGENTS.md` has the conventions.

## MVP verified end to end with Claude Code (third real run, 2026-10-14, sbx v0.48.0)

`scripts/e2e-claude.sh` on the user's macOS host, real `sbx`, real Claude Code in throwaway
sandboxes - every functional check passed:

- reactive registration (`sbx ls` + `sbx secret set-custom --host localhost`) after the first
  "please retry";
- the proxy substitutes the shared placeholder: the pending request carried the **real** sandbox name;
- approval -> daemon's `sbx policy allow network <host> --sandbox <n>` ("Added rule ... [tcp]") ->
  the host became reachable from that sandbox (HTTP 200);
- identity isolation: a second sandbox with the **same** placeholder arrived under its own name
  (sandbox-scoped secrets don't collide despite being keyed by placeholder, #230);
- real Claude Code (`claude -p`): MCP server `✔ Connected`, tool called, request arrived with the
  real name, Claude received "Approved", host reachable afterwards. The earlier "Error POSTing to
  endpoint: EOF" did not recur (cause never pinned down; it appeared only in the run where secrets
  were registered with the wrong `--host`).

The run's only 2 FAILs were the script's own `sbx policy ls <n> | grep <host>` check: v0.48 prints a
summary ("allow 12 hosts from ...") instead of hosts. Fixed: it now tries `policy ls --format json`
and otherwise only notes it - the reachability check is the proof.

## sandbox-kit is a real sbx kit - verified on the host (fourth real run, 2026-10-14)

`sandbox-kit/kits/goversbx-{claude,pi,omp}/` are sbx v2 **mixin** kits. `scripts/e2e-claude.sh`
now validates the kit (`sbx kit validate`), creates both sandboxes with
`sbx create --kit sandbox-kit/kits/goversbx-claude ...` and installs nothing by hand - and every
check passed on the user's host:

- `sbx kit validate`: valid; the v2 mixin composes with the built-in `claude` agent, our install
  command runs after the claude kit's own (`kit=goversbx-claude, user=agent`), the startup fallback
  is queued;
- the kit's `permissions.network.allow` (`localhost:8787`, `host.docker.internal:8787`) alone lets
  the sandbox reach the daemon - no `sbx policy allow` by hand;
- `claude mcp list`: `goversbx-network ... ✔ Connected`; skill at
  `~/.claude/skills/goversbx-network-access/SKILL.md`;
- the whole MCP/identity/approval flow from the MVP run, including real Claude Code, unchanged.

**Found in interactive use afterwards:** asked to `curl spiegel.de`, Claude followed the sandbox's
own `CLAUDE.md` ("Approval required -> ask the user to run `sbx policy approval ...`") and only used
goversbx when told to. Fix (not yet run on the host): the Claude kit now also writes an always-loaded
rule into `~/.claude/CLAUDE.md`; the skill description names the proxy's 403 messages and says to use
it instead of the sbx approval commands, and tells the agent to request redirect targets
(`spiegel.de` -> `www.spiegel.de`) as separate hosts. e2e step 5 now uses a natural prompt that
doesn't mention goversbx ("curl https://<host> and tell me the final HTTP status code"), run from
the workspace, and FAILs if Claude doesn't create a goversbx request on its own. The sandbox's AI
file sits next to the workspace (`<parent>/CLAUDE.md`, plus `kits-agent-context/`), which is why
the earlier search for the mixin's `kits-memory` file looked in the wrong place.

The daemon itself can't be started by a kit (kit hooks run inside the sandbox; the daemon needs the
host's `sbx` CLI and must stay outside the sandbox's control). Instead, the host launcher
`scripts/goversbx run [--goversbx-kit claude|pi|omp] <sbx run args>` starts the daemon in the
background if needed (builds the jar when stale, real mode, `~/.local/state/goversbx/`), then execs
`sbx run --kit ...`; `start|stop|status|logs` manage the daemon. Checked here against `fake-sbx`
(new/existing sandbox, pi kit, non-default port -> `--kit-arg`, foreign process on the port); **not
yet run on the host** - it relies on `sbx run` accepting `--kit` like `sbx create` does.

**Found next (host, `scripts/goversbx run`):** a sandbox removed and created again under the same
name (`test-goversbx`) was never re-registered - the long-running daemon remembered the *name* as
registered, but the new incarnation had lost its sandbox-scoped secret, so every call got "please
retry" (7x `sbx ls`, no `set-custom` in the log) until Claude reported the daemon as unreachable.
Fixed: the daemon remembers registrations by `sbx ls` name **and id**; the retry answer tells the
agent to stop after ~3 tries and point the user at the daemon log; `scripts/goversbx` warns when
the running daemon is older than the code. (Encouraging side note: in that session Claude did reach
for goversbx on its own.)

**Notifications (2026-10-14):** the admin UI subscribes to `GET /api/events` (SSE: `ready`,
`created`, `decided`) and, once the user enables notifications, shows one browser notification per
new request with Approve/Deny actions from a service worker (`sw.js`); notifications close when the
request is decided elsewhere. The daemon side is a listener API in `networkaccess.api`
(`NetworkAccessListener`, `NetworkAccessService.subscribe`) - the hook for later channels (native
macOS, Telegram). Verified: unit + SSE integration tests, and a curl run against the real daemon
(MCP call -> `created` -> approve -> `decided`, tool returned "Approved"). Browser: headless Firefox
157 via Playwright against the real daemon - live indicator, service worker active, pending row
appears without reload, exactly one notification per request (title "<sandbox> wants <host>",
reason as body, tag = request id, actions approve/deny, requireInteraction), closed again after the
approval, request moves to "Decided". Headless Firefox has no OS notification backend
(`showNotification` -> `NS_ERROR_FAILURE`), so that run recorded the notification calls instead; the
UI now catches that error and tells the user to check the OS notification settings; a "Send test
notification" button checks the browser -> OS path on its own.

**Dogfooded in this repo's own sandbox (`claude-goversbx`, 2026-10-14, Firefox on macOS):** asked to
`curl spiegel.de`, Claude used goversbx on its own (no mention of it in the prompt), requested the
redirect target `www.spiegel.de` as a second host, got HTTP 200. A later request (`httpbin.org`)
arrived as a real Firefox notification and was approved from it. Findings:
- The first attempt showed no notification because the daemon/UI hadn't been restarted yet (old
  build without the event stream); fine after the restart.
- `httpbin.org` stayed blocked after the approval: "Blocked by local rule" - a local sbx **deny**
  rule (probably from a declined sbx approval during earlier diagnostics) wins over goversbx's
  allow, yet the daemon reported "Approved". Possible follow-up: detect conflicting deny rules after
  `sbx policy allow` and report them instead of plain "Approved".
- No "please retry" on the first call after a daemon restart is expected: the sandbox-scoped
  identity secret lives in sbx and survives the daemon.

**Next steps**: restart the daemon (`scripts/goversbx stop && scripts/goversbx start`), then rerun
`scripts/e2e-claude.sh` on the host (unprompted goversbx use); pi/oh-my-pi end to end (their
kits exist, never applied with real sbx); then the out-of-scope items (persistence, revocation, admin authn).

### How we got there

**Diagnostic result (`scripts/diagnose-substitution.sh`, sbx v0.48.0, user's host):** for plain-HTTP
traffic from a sandbox to `host.docker.internal:<port>`, `set-custom --host localhost` and `--host **`
are substituted; `--host host.docker.internal` and `--host *.docker.internal` are **not**; `--host`
with a port is rejected (`expected host or IP without scheme/port`). Custom header names are
substituted, not just `Authorization`; HTTPS (httpbin.org) works too. `--env` must match
`^[A-Z_][A-Z0-9_]*$`. curl and Node POSTs worked before and after registration, including with a
`--host host.docker.internal` secret present, so the EOF Claude saw is not reproduced by that alone.
Fix: the daemon registers with `--host localhost` (new default for `GOVERSBX_IDENTITY_TARGET_HOST`).

**Second real run (after the `--host` fix):** registration now succeeds (`sbx secret set-custom
--sandbox <n> --host host.docker.internal ...` exits 0), but the daemon still receives the **raw
placeholder** from both test sandboxes - the proxy does not substitute it on plain-HTTP requests to
`host.docker.internal:8787`. Also new: once the secrets existed, Claude's `claude mcp list` reported
`! Connected · tools fetch failed — Error POSTing to endpoint: EOF` (it was `✔ Connected` in the
first run, when registration had failed) - the registered secret seems to change how the proxy
handles that host. Open hypotheses: the proxy keys plain-HTTP traffic to the host as `localhost`
(the network rule also had to be `localhost:8787`), or substitution only happens on intercepted
HTTPS. `scripts/diagnose-substitution.sh` tests exactly that: one sandbox, a header-echo server on
the host (`scripts/testing/HeaderEcho.java`), one secret per `--host` variant (`host.docker.internal`,
`localhost`, both with port, `*.docker.internal`, `**`), an HTTPS control against httpbin.org, an
`Authorization` variant, and curl vs. Node POSTs before/after registration.

## Automated E2E test

```bash
scripts/e2e-claude.sh            # on the host; needs sbx, java 21+, curl, jq
```

Builds and starts the daemon for real, creates two throwaway Claude sandboxes (`<name>` with a tmp
workspace, `<name>-b` without), lets them reach the daemon, then checks every link with PASS/FAIL:
placeholder -> "please retry" + registration via `sbx ls`/`sbx secret set-custom` -> pending request
carrying the **real** sandbox name (i.e. proxy substitution works) -> auto-approve via the admin
API -> `sbx policy allow` applied and visible in `sbx policy ls` -> host reachable. Step 4b checks
that a request from `<name>-b` arrives as `<name>-b`: both sandboxes share the same placeholder,
and sbx keys custom secrets by placeholder (docker/sbx-releases#230), so this proves sandbox scoping
keeps them apart. Then the same flow with real Claude Code (`claude -p ... --allowedTools
mcp__goversbx-network`) on a second host. Registration is limited to the two test sandboxes
(`GOVERSBX_IDENTITY_SANDBOX_PATTERN`), so the host's other sandboxes are not touched. Removes the
sandboxes (and, best effort, their secrets) and stops the daemon at the end. Knobs: `TARGET_HOST`,
`CLAUDE_TARGET_HOST` (default `example.org`/`example.net`, must be blocked beforehand),
`SKIP_CLAUDE=1`, `KEEP_SANDBOX=1`; logs under `~/.cache/goversbx-e2e/<sandbox>/`.

**First real run (2026-10-14, user's macOS host)** got through steps 1-3 - `sbx create`, `sbx exec`,
`sbx policy allow network localhost:8787 --sandbox`, sandbox -> `host.docker.internal:8787`, the
proxy env in the sandbox, `sbx ls --format json` all work as assumed - and failed at registration:
`sbx secret set-custom` has no `--target` flag (`error: unknown flag: --target`); the current flag is
`--host` (confirmed by a real invocation in docker/sbx-releases#650; `--target` came from an older
example in #7). Fixed in `SbxCliCustomSecretAdapter`. That run also showed the daemon registering
*every* running sandbox on the host (`claude-goversbx`, `omp-secret-manager`, ...) - all failed on
the flag, so nothing changed - which led to the new `GOVERSBX_IDENTITY_SANDBOX_PATTERN` filter.

Checked from inside a sandbox (no real `sbx` here) against the stub `scripts/testing/fake-sbx`,
which now rejects unknown `set-custom` flags like the real CLI: happy path all PASS with unrelated
sandboxes present (they get no secret); substitution disabled -> FAIL "no pending request ...
placeholder is not being substituted"; placeholder-keyed collision simulated -> FAIL "request has
the OTHER test sandbox's name ... approvals would go to the wrong sandbox". Also checked: the Claude
kit install (`claude mcp list` -> Connected) and the exact `claude -p` prompt/`--allowedTools`
against a local daemon. The daemon logs successful sbx calls at INFO, which the script uses.

If step 4b fails for real (secrets collide despite `--sandbox`), the likely fix is a per-sandbox
placeholder derived from the sandbox name in each kit startup script (e.g.
`goversbx-ph-$SANDBOX_NAME`), with the daemon recognizing any `goversbx-ph-*` value as unregistered -
still unforgeable as long as a sandbox-scoped secret only applies to that sandbox's own traffic.

## In progress: nested sbx dev sandbox (2026-10-14)

Goal: let the agent test against a real `sbx` itself instead of handing every host run to the user.
`claude-goversbx` (the current dev sandbox) has no KVM (`kvm: HYP mode not available`). sbx has an
experimental nested-virtualization option (docker/sbx-releases#497, macOS Apple Silicon M3+; the
user's Mac is an M5):

```bash
# on the host
sbx settings set feature.sandbox-nested '{"enabled":true}'
scripts/goversbx run --name goversbx-dev --nested claude ~/IdeaProjects/goversbx
# fallback if `sbx run` doesn't take --nested:
#   sbx create --nested --kit sandbox-kit/kits/goversbx-claude --name goversbx-dev claude ~/IdeaProjects/goversbx
```

Plan inside `goversbx-dev` (an agent resuming here should do this):
1. Check `/dev/kvm` exists (and `dmesg | grep -i kvm` doesn't say "HYP mode not available").
2. Install the Linux sbx (arm64 Ubuntu deb; README: `curl -fsSL https://get.docker.com | sudo
   REPO_ONLY=1 sh && sudo apt-get install docker-sbx && sudo usermod -aG kvm $USER`). Request the
   needed hosts through goversbx - this sandbox has the kit.
3. The user runs `sbx login` once (`! sbx login`), if required.
4. Run an inner daemon against the inner sbx (other ports, e.g. 9787/9788) and the e2e script with
   `SBX=sbx` against inner sandboxes. Then: deny-rule detection, pi/omp kits.

Caveat: the inner sbx is the Linux build, so proxy details (e.g. `host.docker.internal` seen as
`localhost`) may differ from macOS; the user's host stays the reference for final confirmation.

## What exists

One deployable, `daemon/` (Java 21 bytecode, Gradle 9.8.1 wrapper), plus `sandbox-kit/`
(packaging only). The daemon runs on the host, outside every sandbox, with two listeners:

| Port | Bind | Serves |
|------|------|--------|
| 8787 (`GOVERSBX_SANDBOX_API_PORT`) | `0.0.0.0` (`GOVERSBX_SANDBOX_API_BIND`) | MCP endpoint `/mcp` (Streamable HTTP) + `/api/health` - what sandboxes reach |
| 8788 (`GOVERSBX_ADMIN_PORT`) | `127.0.0.1` only | Admin web UI `/`, `GET /api/requests`, `POST /api/requests/{id}/approve\|deny`, `POST /api/sandboxes/{name}/register` |

Three bounded contexts under `run.fabian.daemon`, talking only through `*.api` packages:

- `networkaccess` - request lifecycle (in-memory), approve -> `sbx policy allow network <host> --sandbox <name>`.
- `mcp` - tools `request_network_access(host, reason, timeoutSeconds?)` (blocks up to 120 s for a
  decision) and `check_network_access_status(requestId)`.
- `sandboxidentity` - reactive sandbox identity: the sandbox always sends the literal header
  `X-Goversbx-Sandbox-Name: goversbx-sandbox-identity-placeholder-v1`; when the daemon sees it
  unsubstituted it runs `sbx ls --format json` and `sbx secret set-custom --sandbox <name> --host
  <host> --env GOVERSBX_SANDBOX_TOKEN --placeholder <literal> --value <name>` for every unregistered
  running sandbox matching `GOVERSBX_IDENTITY_SANDBOX_PATTERN` (default: all) and tells the agent
  to retry. From then on the sbx proxy rewrites the
  placeholder to the real sandbox name.

`sandbox-kit/`: sbx v2 mixin kits `kits/goversbx-{claude,pi,omp}/` (spec.yaml + files/), each with a
copy of the shared skill from `skill/goversbx-network-access/` (`syncKitSkills`; `check` fails on
drift). Details in `AGENTS.md`, "Sandbox kit".

### Verified

- 29 automated tests pass (`./gradlew :daemon:test`): domain, application service, admin API and
  registration endpoint via `javalin-testtools`, the MCP endpoint via the MCP Java SDK's own client,
  `sbx ls` JSON parsing against real output.
- Manual curl runs against the running daemon: full MCP Streamable HTTP handshake, header
  propagation into the admin view, reactive-registration retry message.
- pi script against real pi 1.2.0 (`pi mcp list` connects, header sent verbatim, skill discovered).
- sbx behavior from primary sources: `secret set-custom` substitutes placeholders in all outbound
  headers to `--host`, per sandbox, and applies to running sandboxes without restart
  (`docker/sbx-releases` release notes via `gh api`); `sbx ls --format json` shape (user-pasted).

### Never run for real yet

Everything that needs a real `sbx` host: `sbx policy allow`, `sbx secret set-custom`, `sbx ls` from
the daemon (all default to dry-run, `GOVERSBX_SBX_DRY_RUN=true`), proxy substitution of our header,
the network path from a sandbox to the daemon, Claude Code actually calling the tools.

## Manual MVP test runbook (Claude Code) - fallback for debugging the automated test

Goal: an agent in a sandbox asks for a blocked host, a human approves in the web UI, the host
becomes reachable from that sandbox.

**On the host**

1. Build and start the daemon for real (not dry-run), with the identity target set to the
   hostname sandboxes will use to reach it:
   ```bash
   ./gradlew :daemon:shadowJar
   GOVERSBX_SBX_DRY_RUN=false \
   GOVERSBX_IDENTITY_TARGET_HOST=host.docker.internal \
   java -jar daemon/build/libs/goversbx-daemon.jar
   ```
   `sbx` must be on `PATH` (else `GOVERSBX_SBX_BINARY`). If your `sbx` is older than v0.48.0, set
   `GOVERSBX_SBX_LS_FORMAT_ARGS=--json`.
2. Let sandboxes reach the daemon. Sandboxes reach host services as `host.docker.internal`, and
   the port must be allowed by network policy - the sandbox docs phrase this as allowing
   `localhost:<port>`, e.g. `sbx policy allow network localhost:8787` (exact form to confirm).
3. Open http://127.0.0.1:8788 for the admin UI.

**In a Claude Code sandbox**

4. Normally the kit does this (`sbx create --kit sandbox-kit/kits/goversbx-claude ...`). By hand:
   ```bash
   claude mcp add goversbx-network "http://host.docker.internal:8787/mcp" --transport http \
     --header "X-Goversbx-Sandbox-Name: goversbx-sandbox-identity-placeholder-v1" --scope user
   ```
   Copy `sandbox-kit/skill/goversbx-network-access/` to `~/.claude/skills/`. Start a new Claude
   session so the server and skill are loaded; `claude mcp list` should show it connected.
5. Ask Claude to fetch something from a host that is blocked, e.g. "lade https://httpbin.org/get".

**Expected**

6. First tool call: "identity isn't registered yet ... please retry". The daemon log should show
   `sbx ls` and `sbx secret set-custom` for the running sandboxes. Claude retries.
7. Second call: a pending request appears in the admin UI **with the real sandbox name** (not the
   placeholder). Approve it.
8. The daemon runs `sbx policy allow network <host> --sandbox <name>`; the UI shows the sbx output;
   `sbx policy ls` on the host shows the rule; Claude's retried fetch succeeds.

**If it goes wrong**

| Symptom | Likely cause / what to check |
|---------|------------------------------|
| `claude mcp list` shows the server failed | Sandbox can't reach `host.docker.internal:8787`: policy (step 2), daemon bind address, `curl -v http://host.docker.internal:8787/api/health` from the sandbox. |
| "please retry" on every call, never a pending request | Placeholder isn't being substituted: `--host` doesn't match the host the sandbox calls (must be `host.docker.internal` here), the request bypasses the sbx proxy, or `secret set-custom` failed (daemon log; `sbx secret ls`). To keep testing the rest of the flow, temporarily register the MCP server with the real sandbox name as header value. |
| `/mcp` shows goversbx-network "needs authentication" / "Dynamic Client Registration rejected (HTTP 404)" | Claude Code started while the sbx proxy still answered 403 (port not allowed yet) and cached the server as OAuth-protected. Allow the port, then delete the `goversbx-network` entry from `~/.claude/mcp-needs-auth-cache.json` and the `goversbx-network\|...` key under `mcpOAuth` in `~/.claude/.credentials.json`, and restart Claude. With the kit this doesn't happen - the allow-list exists before Claude starts. |
| Request shows sandbox `unknown-sandbox` | Header missing entirely - check the `--header` in `~/.claude.json`. |
| Approved but host still blocked | `sbx policy allow` output in the UI (`sbxApplied`/`sbxOutput`); check `sbx policy ls`. |

## Open questions

1. ~~Sandbox -> daemon network path.~~ **Resolved on a real host:** sandboxes reach the daemon at
   `http://host.docker.internal:8787` through the sbx proxy, after `sbx policy allow network
   localhost:8787 --sandbox <n>`; the proxy treats that traffic as `localhost`, so identity secrets
   use `--host localhost`. (`daemon.internal` from the original design never existed; kit defaults
   are now `host.docker.internal`.)
2. ~~Installing the kit into new sandboxes.~~ **Resolved:** v2 mixin kits (`sandbox-kit/kits/`,
   `sbx create|run --kit <dir|git|zip>` or `sbx kit add`), verified for Claude on the host. Known gap from the kit docs: `sbx kit add` of a mixin doesn't write its
   agent instructions (kits-memory) - harmless here, the skill carries the same guidance.
3. **Daemon state is in-memory** - pending requests and the "already registered" set are lost on
   restart (registration is idempotent, so only one extra retry per sandbox).

## Backlog (after the Claude MVP works)

- Find where mixin agent instructions land; optionally a launchd agent so the daemon starts at login.
- Verify oh-my-pi against a real omp install (needs Bun); add opencode (`~/.config/opencode/opencode.json`, `"type": "remote"`).
- Out of MVP scope so far: revocation/expiry of approvals, persistence, authn on the admin UI
  (currently only protected by loopback binding), audit log, deny reason back to the agent.
