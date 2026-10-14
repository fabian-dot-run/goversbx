# goversbx

Lets an agent running inside an `sbx`-managed Docker sandbox proactively request outbound
network access to a specific host, with a human approving or denying the request from a small
local web UI. On approval, a daemon running outside the sandbox shells out to the `sbx` CLI to
actually grant it (`sbx policy allow network <host> --sandbox <name>`).

See [`docs/plan.md`](docs/plan.md) for the full design and rationale.

## Modules

| Module        | Runs where         | Purpose                                                             |
|---------------|---------------------|----------------------------------------------------------------------|
| `common`      | both                 | Shared `NetworkAccessRequest` model + JSON (de)serialization         |
| `daemon`      | host, outside sandboxes | Receives requests, serves the approve/deny web UI, calls `sbx`   |
| `mcp-server`  | inside each sandbox  | MCP server (stdio) exposing the two tools to the agent               |
| `sandbox-kit` | packaging only       | `SKILL.md` + launcher + draft startup hook, bundled into a zip       |

## Building

Requires a JDK on `PATH` (JDK 21+; bytecode is compiled with `--release 21` regardless of the
JDK actually running Gradle, so no JDK 21 toolchain download is needed).

```bash
./gradlew build -x test
```

## Running the daemon

```bash
./gradlew :daemon:shadowJar
java -jar daemon/build/libs/goversbx-daemon.jar
```

Two HTTP listeners start:

- **Sandbox-facing API** — `0.0.0.0:8787` by default (`GOVERSBX_SANDBOX_API_PORT`,
  `GOVERSBX_SANDBOX_API_BIND`). This is what sandboxes reach via the always-allow-listed
  hostname.
- **Admin UI + API** — `127.0.0.1:8788` only (`GOVERSBX_ADMIN_PORT`), never exposed to a
  sandbox. Open it in a browser to approve/deny pending requests.

By default `GOVERSBX_SBX_DRY_RUN=true`, so approvals log what `sbx policy allow ...` *would* run
without calling it. Set it to `false` (and `GOVERSBX_SBX_BINARY` if `sbx` isn't on `PATH`) to
apply real policy changes.

## Running the MCP server

```bash
./gradlew :mcp-server:shadowJar
GOVERSBX_DAEMON_URL=http://localhost:8787 SANDBOX_NAME=my-sandbox \
  java -jar mcp-server/build/libs/goversbx-mcp-server.jar
```

Inside a real sandbox this is registered automatically via the `sandbox-kit` startup hook
(`claude mcp add goversbx-network <launcher> --scope user`). To wire it into a local Claude Code
session manually for testing:

```bash
claude mcp add goversbx-network "java -jar $(pwd)/mcp-server/build/libs/goversbx-mcp-server.jar" --scope user
```

Exposes two tools: `request_network_access(host, reason, timeoutSeconds?)` and
`check_network_access_status(requestId)`.

## Packaging the sandbox kit

```bash
./gradlew :sandbox-kit:packageKit
```

Produces `sandbox-kit/build/distributions/goversbx-sandbox-kit-*.zip` containing the mcp-server
jar, its launcher script, `SKILL.md`, and a **draft** durable-startup command — the real host-side
mechanism for registering a new `sbx` kit still needs to be confirmed (see plan, open questions).

## Status

Milestones M1–M4 from the plan are implemented and manually verified end-to-end (curl-driven
create/approve/deny flow, and a real MCP JSON-RPC stdio smoke test against a running daemon). M5
(wiring the real `sbx policy allow` call against a live sandbox, and resolving the host-side kit
registration mechanism) is not yet done.
