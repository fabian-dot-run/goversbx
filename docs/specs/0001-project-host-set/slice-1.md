# Slice 1: batch request from the project file

Status: implementing (2026-10-23; decisions below taken by the user, code and contract tests in
place, the e2e run on a real sbx is pending). Epic: `epic.md`.

## Decisions (2026-10-23)

1. **Unified request entity.** `NetworkAccessRequest` carries one or more hosts, an origin
   (`AGENT` / `PROJECT_FILE`), the source file and profile names, and per-host outcomes
   (`HostGrant`). An agent request is a set of one. One approval flow, one repository, one view with
   `hosts`; `host` stays set for single requests and is `null` for a set.
2. **sbx idempotency** (`sbx policy allow` for an existing rule): verified by
   `SbxCliContractTest.policyAllowNetworkTwiceForTheSameHostSucceedsBothTimes` in the e2e suite.
3. **Comma-separated list with `--sandbox`**: verified by reachability in
   `ProjectHostSetTest.aCommaSeparatedHostListIsAcceptedBySbxPolicyAllowWithSandbox` (e2e). The daemon
   applies per host until that run is green; then the batch form is an adapter-only change
   (`SbxPolicyPort.allowNetwork(sandbox, hosts)` is the seam) and this spec is updated.
4. **Profiles only in the config dir** (`$XDG_CONFIG_HOME/goversbx/profiles/`, default
   `~/.config/goversbx/profiles/`); no `profilesDir` setting.

Amendments while implementing:

- T1 includes `POST /api/sandboxes/{name}/register` only when `sbx ls` lists the sandbox: the
  endpoint now looks the name up, and records and announces the incarnation if it is new. A
  pre-warmed name sbx doesn't list is registered without an announcement (no workspaces to read).
- T2 answers `404` both for a name the daemon never registered and for one sbx no longer lists.
  Its response lists one evaluation per workspace (`CREATED`, `ALREADY_PENDING`, `ALL_GRANTED`,
  `NO_FILE`, `INVALID` with the problems).
- The F3 admin-port guard moved from the MCP tool into `networkaccess` (`LocalTargetGuard`,
  applied by the service on every path), so a project file cannot name the daemon's own admin port
  either.
- Scenario 14: the contract suite pins the API and SSE shape of a set (every host, origin, source,
  profiles); the rendering itself (list in full, distinct styling, "Approve all") is checked by
  hand in the dashboard, since the suite has no browser.

## Scope

- A project file `<workspace>/.goversbx/network.yaml` declaring hosts and/or user profiles.
- User profiles `~/.config/goversbx/profiles/<name>.yaml` (`$XDG_CONFIG_HOME` honored, like the
  daemon config).
- A **host-set request**: one request carrying several hosts, decided as a whole, applied per host.
- Trigger: every new sandbox registration, plus an admin endpoint for an explicit (re)trigger.
- Dashboard and notifications show a set as one item.
- Single requests and sets know about each other: an approved set resolves pending single
  requests for its hosts, and a single request for an already granted host is answered at once.

Not in this slice: launcher trigger (slice 2), auto-apply (slice 3), partial approval, shipped
profiles, comma-list `sbx policy allow` (optimization, needs a contract test first).

## File contract

```yaml
# <workspace>/.goversbx/network.yaml
version: 1
profiles:            # optional; names of user profiles, [a-z0-9-]{1,64}
  - jvm-build
hosts:               # optional; at least one of profiles/hosts must be present
  - repo.maven.apache.org
  - plugins.gradle.org
```

```yaml
# ~/.config/goversbx/profiles/jvm-build.yaml
version: 1
hosts:
  - services.gradle.org
  - plugins-artifacts.gradle.org
```

Rules:

- `version` must be `1`. Unknown keys are an error (typos must not silently drop hosts).
- Every host must pass `HostValidation` (plain DNS name, optional port; no wildcards, URLs, IPs)
  and the F3 admin-port guard. One invalid entry invalidates the whole file.
- The resolved set is the union of all profile hosts and the file's hosts, in that order,
  deduplicated, at most 100 hosts. Profiles cannot reference profiles.
- Both files must be regular files of at most 64 KiB; symlinks are not followed. A missing project
  file means "no set" and is not an error. A missing profile is an error.
- An invalid file never produces a request. The error (path, reason) goes to the daemon log, and to
  the admin endpoint's response when that was the trigger. The agent side sees nothing: the file
  is agent-writable, so its errors are the user's to read, not the agent's to fix by trial.

## Behavior

**Trigger T1, registration.** Whenever `sandboxidentity` registers a new incarnation (`name@id`,
through the reactive path, the startup sweep or `POST /api/sandboxes/{name}/register`), the
daemon takes the sandbox's `workspaces` from the `sbx ls` output it already parsed, and for every
workspace whose `.goversbx/network.yaml` resolves to a non-empty set it creates one host-set
request. A recreated sandbox (new id) is a new incarnation and gets a new request.

**Trigger T2, admin endpoint.** `POST /api/sandboxes/{name}/host-set-requests` (admin token)
evaluates the same for a registered sandbox: `201` with the created request(s); `200` with the
existing request if one is still pending for the same sandbox and file; `200` and nothing created
when there is no file or every host is already granted; `400` with the parse/validation errors;
`404` for an unregistered sandbox. Slice 2 calls this from the launcher.

**Already granted.** The service tracks the hosts granted to a sandbox incarnation in this daemon
process (approved and applied requests, single or set). Hosts already granted are left out of a
new set; after a restart this knowledge is gone and a re-trigger re-requests the full set
(harmless: sbx already has the rules; documented as a known limitation until slice 3's
persistence).

**Decision.** A set is approved or denied as a whole, by the human, every time. On approval the
daemon runs `sbx policy allow network <host> --sandbox <name>` per host, sequentially, records
each host's outcome, and the request is `approved` with `sbxApplied = true` only if every host
applied. Failed hosts stay requestable by the agent as singles. Pending single requests of the
same sandbox whose host is in the approved set are resolved by that decision (same `decidedBy`,
the host's outcome), and their waiting tool calls return. Denial runs no sbx command, touches no
single request, and leaves T2 free to create a new request later (the user may have edited the
file).

**Single request for a granted host.** `request_network_access` for a host already granted to
the calling sandbox returns "approved" immediately, with no pending request and no human
involvement.

**Dashboard.** A set is one item in the list and the SSE stream, visibly different from a single
request (a set is a broader grant; threat-model F2 asks for distinct styling). It shows the
verified sandbox name, the origin (project file path, profile names), and **every host**, not a
count with a collapsed list, before the approve/deny buttons. Browser notifications carry the host
count. Auto-open applies as for singles.

**Logging.** Every applied host is logged as today; the set logs its origin file.

## Security notes

- The project file is the agent's to write. Slice 1 therefore never applies it without a human
  reading the full list. Strict parsing, the host validation and the 100-host cap keep the list
  honest and reviewable; a long list is still the human's call.
- Profile files live outside the sandbox and are the user's own. They are still shown in full.
- The workspace path comes from `sbx ls`, not from the agent, so the daemon reads only
  `<workspace>/.goversbx/network.yaml`, never a path the agent chose. Symlinks are not followed,
  so the file cannot be pointed at something else to be read.
- Threat model: add the project file as an attacker-influenced input under F4 when the slice
  lands.

## Scenarios

1. **Set from the project file.** Given a running sandbox whose workspace has a valid file with
   three hosts and no profiles, when the sandbox registers, then one pending request with those
   three hosts and origin "project file" exists for that sandbox name. `contract`
2. **Profiles resolve.** Given a profile `jvm-build` with two hosts and a project file naming it
   plus one host, when the set resolves, then it holds the two profile hosts followed by the file's
   host. `unit`
3. **Deduplication and cap.** Given a file whose profile and host list overlap, when the set
   resolves, then each host appears once; given more than 100 distinct hosts, then the file is
   invalid. `unit`
4. **Invalid file produces nothing.** Given a file with a wildcard host, an unknown key, an
   unknown profile or `version: 2`, when the sandbox registers, then no request exists and the
   daemon log names the file and the reason. `unit` for each reason, `contract` for one.
5. **No file, no request.** Given a workspace without the file, when the sandbox registers, then
   no request exists and nothing is logged above debug. `contract`
6. **Approval applies every host.** Given a pending set of three hosts, when the human approves
   it, then `FakeSbx` recorded exactly three `policy allow network <host> --sandbox <name>` calls in
   file order and the request is `approved` with `sbxApplied` true. `contract`
7. **Partial sbx failure is visible.** Given `FakeSbx` fails for the second host, when the set
   is approved, then the request is `approved` with `sbxApplied` false, the per-host outcomes name
   the failed host, and the other two were applied. `contract`
8. **Denial applies nothing.** Given a pending set, when the human denies it, then no sbx call
   was recorded and the request is `denied`. `contract`
9. **Set resolves pending singles.** Given a pending single request from sandbox A for host H
   and a pending set for A containing H, when the set is approved, then the single request is
   `approved` with the same `decidedBy`, and a tool call waiting on it returns "approved". `contract`
10. **Granted host answers at once.** Given sandbox A has an approved and applied grant for H,
    when A calls `request_network_access` for H, then the tool returns approved without a new
    pending request. `contract`
11. **Isolation.** Given sandbox B has no file, when A's set is approved, then B has no grants and
    no requests. `contract`
12. **Explicit trigger.** Given a registered sandbox with a valid file, when the admin endpoint is
    called, then `201` with the request; called again while pending, then `200` with the same
    request; after approval, then `200` and no new request; with an invalid file, then `400` naming
    the error; for an unregistered name, then `404`. `contract`
13. **Recreated sandbox gets a fresh set.** Given a sandbox was recreated under the same name
    (new id), when the new incarnation registers, then a new pending set exists even though the old
    incarnation's set was approved. `contract`
14. **Dashboard shows the whole set.** Given a pending set of five hosts, when the dashboard
    renders it, then all five hosts, the origin path and the profile names are in the DOM before
    the buttons, and the item carries the set styling; the SSE stream delivered it as one event.
    `contract`
15. **End to end.** Given the e2e sandbox `-a` created with the Claude kit and a workspace that
    contains a file with the target host, when `-a` registers and the set is approved, then the
    target host answers 200 from `-a` and stays blocked from `-b`. `e2e`
16. **Restart.** Given an approved set before `stop`+`start`, when the admin endpoint is called
    after the restart, then the full set is requested again (known limitation, pinned so a change
    is deliberate). `contract`

## Design notes (not binding)

- Preferred modeling: generalize `NetworkAccessRequest` to a list of hosts with an `origin`
  (`agent` / `project-file`) and per-host outcomes, a single request being a list of one. One
  approval flow, one repository, one API view with a `hosts` array; the dashboard switches on the
  size/origin. Avoids a parallel entity and a second approve/deny path.
- Reading the file belongs to a new, thin bounded context (`projecthosts`: file + profile
  resolution, no sbx) exposing a published `api`; `sandboxidentity` notifies through a listener
  on registration and `networkaccess` creates the request. `networkaccess` must not read files.
- YAML needs a parser; `jackson-dataformat-yaml` is the natural fit on the existing Jackson, with
  the tree model (no reflection on our types, same reasoning as the registration store). New
  routes and JSON shapes need contract tests, or the native binary will lack their metadata.

## Open questions

Settled, see "Decisions" above. Questions 2 and 3 have their verifying tests in the e2e suite and
are answered by its first green run on a real sbx.
