# Implementation status

Snapshot as of 2026-10-14. This is the place to resume from. `docs/plan.md` has the full history
and rationale (dated revision sections); `AGENTS.md` has the conventions.

**Next step: run `scripts/e2e-claude.sh` on the sbx host** (see "Automated E2E test" below) - the
first run of everything against real `sbx`. The manual runbook further down is the fallback for
debugging. pi/oh-my-pi support exists but is parked until the Claude path works end to end.

## Automated E2E test

```bash
scripts/e2e-claude.sh            # on the host; needs sbx, java 21+, curl, jq
```

Builds and starts the daemon for real, creates a throwaway Claude sandbox (`sbx create --name
goversbx-e2e-<ts> claude <tmp workspace>`), lets it reach the daemon, then checks every link with
PASS/FAIL: placeholder -> "please retry" + registration via `sbx ls`/`sbx secret set-custom` ->
pending request carrying the **real** sandbox name (i.e. proxy substitution works) -> auto-approve
via the admin API -> `sbx policy allow` applied and visible in `sbx policy ls` -> host reachable.
Then the same with real Claude Code (`claude -p ... --allowedTools mcp__goversbx-network`) on a
second host. Removes the sandbox and stops the daemon at the end. Knobs: `TARGET_HOST`,
`CLAUDE_TARGET_HOST` (default `example.org`/`example.net`, must be blocked beforehand),
`SKIP_CLAUDE=1`, `KEEP_SANDBOX=1`; logs under `~/.cache/goversbx-e2e/<sandbox>/`.

Already checked from inside a sandbox (no real `sbx` here): the script's mechanics against a stub
`sbx` that simulates proxy substitution (all steps PASS) and with substitution disabled (fails at
"no pending request ... placeholder is not being substituted"); the Claude kit install (`claude
mcp list` -> Connected) and the exact `claude -p` prompt/`--allowedTools` against a local daemon
(Claude called the tool, request appeared, approved, Claude printed "Approved"). Still assumed from
sbx docs: the exact `sbx create`/`exec`/`rm --force`/`policy allow --sandbox` syntax - a wrong
assumption shows up as a FAIL with sbx's own error output. The daemon now logs successful sbx calls
at INFO (`sbx ls: running sandboxes ...`, `sbx secret set-custom registered ...`, `sbx policy allow
network ... succeeded`), which the script uses.

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
  unsubstituted it runs `sbx ls --format json` and `sbx secret set-custom --sandbox <name> --target
  <host> --env GOVERSBX_SANDBOX_TOKEN --placeholder <literal> --value <name>` for every unregistered
  running sandbox and tells the agent to retry. From then on the sbx proxy rewrites the
  placeholder to the real sandbox name.

`sandbox-kit/`: shared `skill/goversbx-network-access/SKILL.md` plus `agents/{claude,pi,omp}/startup.sh`
(drafts - the host-side way to install a kit into new sandboxes is not confirmed yet).

### Verified

- 29 automated tests pass (`./gradlew :daemon:test`): domain, application service, admin API and
  registration endpoint via `javalin-testtools`, the MCP endpoint via the MCP Java SDK's own client,
  `sbx ls` JSON parsing against real output.
- Manual curl runs against the running daemon: full MCP Streamable HTTP handshake, header
  propagation into the admin view, reactive-registration retry message.
- pi script against real pi 1.2.0 (`pi mcp list` connects, header sent verbatim, skill discovered).
- sbx behavior from primary sources: `secret set-custom` substitutes placeholders in all outbound
  headers to `--target`, per sandbox, and applies to running sandboxes without restart
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

4. Register the MCP server (the kit's `agents/claude/startup.sh` does exactly this, with
   `GOVERSBX_DAEMON_HOST=host.docker.internal`):
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
| "please retry" on every call, never a pending request | Placeholder isn't being substituted: `--target` doesn't match the host the sandbox calls (must be `host.docker.internal` here), the request bypasses the sbx proxy, or `secret set-custom` failed (daemon log; `sbx secret ls`). To keep testing the rest of the flow, temporarily register the MCP server with the real sandbox name as header value. |
| Request shows sandbox `unknown-sandbox` | Header missing entirely - check the `--header` in `~/.claude.json`. |
| Approved but host still blocked | `sbx policy allow` output in the UI (`sbxApplied`/`sbxOutput`); check `sbx policy ls`. |

## Open questions

1. **Sandbox -> daemon network path.** Design assumed `daemon.internal`; for the test we use
   `host.docker.internal`. To confirm: exact policy command, and that this traffic goes through the
   sbx proxy (required for header substitution - Claude Code's Node HTTP client honors the
   sandbox's `HTTP(S)_PROXY` via `NODE_USE_ENV_PROXY=1`).
2. **Installing the kit into new sandboxes.** sbx kits are `spec.yaml` (schemaVersion "2") applied
   with `sbx run --kit <git|dir|oci> <agent>`; recent sbx can add kits with startup commands to
   existing sandboxes. Our `startup.sh`/`manifest-fragment.json` drafts are not a real kit yet - a
   `mixin`-style kit on top of the agent kits is the likely shape (see `docker/sbx-kits-contrib`).
3. **Daemon state is in-memory** - pending requests and the "already registered" set are lost on
   restart (registration is idempotent, so only one extra retry per sandbox).

## Backlog (after the Claude MVP works)

- Turn `sandbox-kit` into a real sbx kit (open question 2), then remove the "DRAFT" markers.
- Verify oh-my-pi against a real omp install (needs Bun); add opencode (`~/.config/opencode/opencode.json`, `"type": "remote"`).
- Out of MVP scope so far: revocation/expiry of approvals, persistence, authn on the admin UI
  (currently only protected by loopback binding), audit log, deny reason back to the agent.
