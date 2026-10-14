# Implementation status

Snapshot as of 2026-10-14. This is the place to resume from. `docs/plan.md` has the full history
and rationale (dated revision sections); `AGENTS.md` has the conventions.

**Next step: re-run `scripts/e2e-claude.sh` on the sbx host** with the `--host localhost` fix from
the diagnostic below. Still open from the last run: Claude's "Error POSTing to endpoint: EOF".
pi/oh-my-pi support exists but is parked until the Claude path works.

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
| "please retry" on every call, never a pending request | Placeholder isn't being substituted: `--host` doesn't match the host the sandbox calls (must be `host.docker.internal` here), the request bypasses the sbx proxy, or `secret set-custom` failed (daemon log; `sbx secret ls`). To keep testing the rest of the flow, temporarily register the MCP server with the real sandbox name as header value. |
| Request shows sandbox `unknown-sandbox` | Header missing entirely - check the `--header` in `~/.claude.json`. |
| Approved but host still blocked | `sbx policy allow` output in the UI (`sbxApplied`/`sbxOutput`); check `sbx policy ls`. |

## Open questions

1. ~~Sandbox -> daemon network path.~~ **Resolved on a real host:** sandboxes reach the daemon at
   `http://host.docker.internal:8787` through the sbx proxy, after `sbx policy allow network
   localhost:8787 --sandbox <n>`; the proxy treats that traffic as `localhost`, so identity secrets
   use `--host localhost`. (`daemon.internal` from the original design never existed; kit defaults
   are now `host.docker.internal`.)
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
