# Slice 1: remove project host sets

Status: done (2026-11-10). `./gradlew check` is green (unit, contract, launcher, kit skill drift) and
`:daemon:e2eTest` is green against real sbx 0.51.0 in the nested dev sandbox (together with slice 2;
`ClaudeAgentTest` skipped as usual). Not run: `binaryTestNative`, and the dashboard was not opened
in a browser.

## Problem

Epic 0001 added project host sets: `<workspace>/.goversbx/network.yaml` plus user profiles became
one multi-host request per sandbox. Nobody uses it and it costs a bounded context, a second config
file, a launcher trigger and a second kind of row in the dashboard. Decision D10 of this epic drops
it, including the instant approval of a request for a host already granted to the sandbox.

## Scope

Pure removal. No new behaviour, no version bump, no kit change. Works on sbx 0.49.

### Removed

- The `projecthosts` bounded context, `.goversbx/network.yaml` and
  `~/.config/goversbx/profiles/` handling, `jackson-dataformat-yaml`.
- `POST /api/sandboxes/{name}/host-set-requests` (and `?format=text`).
- In `networkaccess`: the multi-host request (`hosts`, `HostGrant`, `RequestOrigin`,
  `HostSetRequestOutcome`, `createHostSetRequest`), the per-sandbox memory of granted hosts
  (`grantedHosts`, `forgetGrants`), "an approved set resolves pending singles", and the instant
  approval of a request for an already granted host (`decidedBy = "goversbx"`). `SbxPolicyPort`
  allows one host per call; `SbxCliPolicyAdapter` has no comma list and no per-host fallback.
- In `sandboxidentity`: `SandboxRegistrationListener`, `RegisteredSandbox`, `findRegistered`, the
  `workspaces` and non-running rows of `sbx ls` (`ListedSandbox` is `RunningSandbox` again). The
  admin `register` call no longer runs `sbx ls` to record the incarnation.
- Dashboard: set rows, the "project host set" badge and hint, "Approve all", per-host outcomes.
- Launcher: `host_set_summary` and its output; nothing is asked of the daemon for an existing sandbox.
- The request JSON of the admin API is back to its pre-0.3.0 shape: one `host`; no `hosts`,
  `origin`, `source`, `profiles`, `hostResults`.

### Stays

- `domain.LocalTargetGuard` (threat-model F3) in the `networkaccess` domain, applied by the service
  to every request (`hostRejectionReason`, `createRequest`).
- `POST /api/sandboxes/{name}/register` (pre-warming) and the launcher's
  create -> register -> attach sequence, now only so the identity secret exists before the agent's
  first call. Registration persistence and everything else about identity.
- The MCP tools' contract: `request_network_access(host, reason, timeoutSeconds?)` and
  `check_network_access_status(requestId)`.
- `NetworkAccessService.approve` refuses an already decided request before calling sbx (it came in
  with epic 0001 but is not about sets).
- Epic 0001's spec files, as the record of what was built.

## Scenarios

| # | Scenario | Level |
|---|---|---|
| S1 | Given a sandbox whose request for `a.example` was approved, when it requests `a.example` again, then the new request is pending, has no `decidedBy`, and approving it calls sbx a second time | `unit` (`NetworkAccessServiceTest`), `contract` (`AdminApiTest.aHostApprovedEarlierIsAskedForAgain`) |
| S2 | Given a daemon with admin port 8788, when a request names `localhost:8788` or a bare host-local alias, then it is rejected and no request exists; `host.docker.internal:3000` is accepted | `unit` (`LocalTargetGuardTest`, `NetworkAccessServiceTest.theAdminPortGuardIsPartOfTheHostRule`), `contract` (`McpProtocolTest.requestingTheDaemonsOwnAdminPortIsRejected`) |
| S3 | Given a pending request, when the admin API lists it, then it carries `host` and none of `hosts`, `origin`, `source`, `profiles`, `hostResults` | `contract` (`AdminApiTest.pendingRequestHasFullShape`) |
| S4 | Given a running daemon, when `POST /api/sandboxes/{name}/host-set-requests` is called with the admin token (with or without `?format=text`), then it answers 404 | `contract` (`AdminApiTest.theHostSetEndpointIsGone`) |
| S5 | Given a running daemon, when `POST /api/sandboxes/{name}/register` is called, then the identity secret is set for that sandbox | `contract` (`AdminApiTest.registerEndpointRegistersOneSandbox`) |
| S6 | Given a new sandbox, when `goversbx run` starts it, then the order is `sbx create`, the daemon's `register`, `sbx run <name>` | `launcher` (`test-launcher.sh` 1, 6) |
| S7 | Given a workspace that still has a `.goversbx/network.yaml`, when `goversbx run` creates a sandbox for it, then the launcher makes no host-set call, prints nothing about host sets and the daemon holds no request | `launcher` (`test-launcher.sh` 2) |
| S8 | Given an existing sandbox, when `goversbx run <name>` starts it, then the launcher calls nothing on the daemon about it | `launcher` (`test-launcher.sh` 5) |
| S9 | Given a real sbx, when the real launcher creates a sandbox for a workspace with a leftover `.goversbx/network.yaml`, then no request exists before the sandbox asks, and its first `request_network_access` is pending under its own name without "please retry" | `e2e` (`LauncherSequenceTest`) - compiled, not yet run |

## Open questions

None.
