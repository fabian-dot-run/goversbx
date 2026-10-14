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
  sandbox. Open it in a browser to approve/deny pending requests.

By default `GOVERSBX_SBX_DRY_RUN=true`, so approvals and sandbox-identity registration log what
they *would* run without calling `sbx`. Set it to `false` (and `GOVERSBX_SBX_BINARY` if `sbx` isn't
on `PATH`) to apply real changes.

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
# or: sbx run --kit ..., or add it to an existing sandbox: sbx kit add ...
```

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

## Status

**Current state, the Claude Code MVP test runbook, and open questions: [`docs/status.md`](docs/status.md).**
End-to-end test against a real sbx host: `scripts/e2e-claude.sh` (run on the host).

**The Claude Code MVP is verified end to end against a real sbx host (2026-10-14):** reactive
sandbox identity, proxy substitution, approval -> `sbx policy allow` -> host reachable, identity
isolation between sandboxes, and real Claude Code calling the tools. Automated: unit tests plus
`javalin-testtools` integration tests for the admin API, the registration endpoint and the MCP
endpoint (`./gradlew check`). The Claude kit is verified on the host too (`sbx create --kit`, nothing installed by hand).
