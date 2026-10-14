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
| `sandbox-kit` | packaging only           | `SKILL.md` + draft durable-startup hook, bundled into a zip            |

### Inside `daemon`: bounded contexts, hexagonal layering

- `networkaccess` - the approval workflow. `domain` (entity + invariants) →
  `application` (use-case service + driven ports) → `adapter` (in-memory repository, `sbx` CLI
  adapter, admin web controller). Publishes a narrow `api` package
  (`NetworkAccessApi` + `NetworkAccessRequestView`) - the **only** thing another bounded context
  may depend on.
- `mcp` - pure MCP-protocol translation against `networkaccess.api`. Has no domain of its own by
  design; it exists purely to adapt MCP tool calls into calls on the published API.
- `sandboxidentity` - registers a sandbox's proxy-verified identity secret
  (`sbx secret set-custom`) before that sandbox starts. Operator-facing, admin-only; `mcp` doesn't
  call into it directly - it just reads the header the registration makes trustworthy.

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

### Registering a sandbox's verified identity

Before a sandbox starts, register it so its MCP requests carry a proxy-verified
`X-Goversbx-Sandbox-Name` header instead of a self-reported one:

```bash
curl -X POST http://localhost:8788/api/sandboxes/<sandbox-name>/register
```

This runs `sbx secret set-custom --sandbox <name> --target daemon.internal --env
GOVERSBX_SANDBOX_TOKEN --placeholder ... --value <name>` under the hood - the sbx proxy then
rewrites the placeholder to the real sandbox name on every outbound header to the daemon, so the
sandbox itself never sees or controls the real value (see `docs/plan.md`'s second 2026-10-14
revision). `GOVERSBX_IDENTITY_TARGET_HOST`/`_ENV_VAR`/`_PLACEHOLDER` customize the details.

To register the MCP endpoint in a local Claude Code session for manual testing:

```bash
claude mcp add goversbx-network "http://localhost:8787/mcp" --transport http \
  --header "X-Goversbx-Sandbox-Name: my-sandbox" --scope user
```

Exposes two tools: `request_network_access(host, reason, timeoutSeconds?)` and
`check_network_access_status(requestId)`.

## Packaging the sandbox kit

```bash
./gradlew :sandbox-kit:packageKit
```

Produces `sandbox-kit/build/distributions/goversbx-sandbox-kit-*.zip` containing `SKILL.md` and a
**draft** durable-startup command that registers the MCP endpoint - the real host-side mechanism
for registering a new `sbx` kit still needs to be confirmed (see `docs/plan.md`, open questions).

## Status

Milestones M1–M4 from the original plan, the revision that merged the MCP server into the daemon,
and the revision that added verified sandbox identity via `sbx secret set-custom` are all
implemented and verified: unit tests for the domain/application layers, `javalin-testtools`
integration tests for the admin REST API, the registration endpoint, and the real MCP HTTP
endpoint (23 tests total, `./gradlew :daemon:test`), plus manual end-to-end curl sessions for both
the MCP Streamable HTTP handshake and the registration endpoint. M5 (wiring the real `sbx policy
allow` call against a live sandbox, and resolving the host-side kit registration mechanism) is not
yet done; nor is wiring the registration step into actual sandbox provisioning (currently a manual
`curl`/API call an operator makes before a sandbox starts).
