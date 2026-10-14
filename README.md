# goversbx

Lets an agent running inside an `sbx`-managed Docker sandbox proactively request outbound
network access to a specific host, with a human approving or denying the request from a small
local web UI. On approval, the daemon - which runs entirely outside every sandbox - shells out to
the `sbx` CLI to actually grant it (`sbx policy allow network <host> --sandbox <name>`).

The daemon also serves the MCP protocol surface sandboxes talk to, over HTTP, the same way the
platform's own `mcp-gateway` does - there is no process or jar running inside the sandbox at all.

See [`docs/plan.md`](docs/plan.md) for the full design and rationale, including the 2026-10-14
revision that moved the MCP server into the daemon.

## Modules

| Module        | Runs where              | Purpose                                                             |
|---------------|--------------------------|-----------------------------------------------------------------------|
| `daemon`      | host, outside sandboxes | Everything: the approval workflow and the MCP endpoint, as two bounded contexts (see below) |
| `sandbox-kit` | packaging only           | `SKILL.md` + draft durable-startup hook, bundled into a zip            |

### Inside `daemon`: two bounded contexts, hexagonal layering

- `networkaccess` - the approval workflow. `domain` (entity + invariants) →
  `application` (use-case service + driven ports) → `adapter` (in-memory repository, `sbx` CLI
  adapter, admin web controller). Publishes a narrow `api` package
  (`NetworkAccessApi` + `NetworkAccessRequestView`) - the **only** thing another bounded context
  may depend on.
- `mcp` - pure MCP-protocol translation against `networkaccess.api`. Has no domain of its own by
  design; it exists purely to adapt MCP tool calls into calls on the published API.

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

By default `GOVERSBX_SBX_DRY_RUN=true`, so approvals log what `sbx policy allow ...` *would* run
without calling it. Set it to `false` (and `GOVERSBX_SBX_BINARY` if `sbx` isn't on `PATH`) to
apply real policy changes.

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

Milestones M1–M4 from the original plan, plus the 2026-10-14 revision that merged the MCP server
into the daemon as a second bounded context, are implemented and verified: unit tests for the
domain/application layers, `javalin-testtools` integration tests for both the admin REST API and
the real MCP HTTP endpoint (20 tests total, `./gradlew :daemon:test`), and a manual end-to-end
curl session exercising the full MCP Streamable HTTP handshake. M5 (wiring the real `sbx policy
allow` call against a live sandbox, and resolving the host-side kit registration mechanism) is not
yet done.

**Known risk, accepted for the MVP**: the daemon trusts whatever sandbox-name header a request
carries - there is no independent check that it actually came from that sandbox. See
`docs/plan.md`, open question 3, for why this isn't just a transport-specific quirk and what fixing
it would require.
