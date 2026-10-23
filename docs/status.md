# Implementation status

Snapshot as of 2026-10-23. This is the place to resume from. `docs/plan.md` has the full history
and rationale (dated revision sections); `AGENTS.md` has the conventions.

## Slice 1 of epic 0001 done (2026-10-23, e2e green on the host, not yet released)

The e2e suite ran green on the user's macOS host against sbx 0.49.1 (27 passed, `ClaudeAgentTest`
skipped as usual, 1m23s): the project file raised one set on re-registration, approval ran
`sbx policy allow` and `example.com` answered 200 from `-a` only, the explicit trigger reported
`ALL_GRANTED`, the agent's request for the granted host was approved at once. **Both spec questions
are answered**: a second `policy allow` for the same host prints "Already covered in policy local"
with exit 0 (idempotent), and `policy allow network a,b --sandbox` creates one rule per host, prints
one line per host, both hosts reachable. So `SbxCliPolicyAdapter` now applies a set with ONE call
(comma list, each host's line as its outcome) and falls back to per-host calls only if that fails
(`FakeSbx.failPolicyFor` matches inside a comma list; contract tests pin both shapes). Also removed a
stray `hostSet` JSON property Jackson had derived from a view method (seen in the e2e output).
`./gradlew check` green (108 unit, 77 contract). **Next**: the user looks at a set in the dashboard
once (scenario 14), bump the version (kit specs 0.2.7, MCP server info 0.2.7, README says 0.2.8
unreleased), release, then slice 2 (launcher trigger: `goversbx run` registers the sandbox right
after `sbx create` and calls `POST /api/sandboxes/{name}/host-set-requests`).

## Slice 1 of epic 0001 implemented (2026-10-23, superseded by the entry above)

The user settled the spec's open questions (unified request entity; profiles only in the config
dir; verify sbx idempotency and the comma list, use the comma form right away if it works). Built
against `docs/specs/0001-project-host-set/slice-1.md`, all its scenarios have tests:

- `networkaccess`: `NetworkAccessRequest` now has `hosts` + `origin` + per-host `HostGrant`s (an
  agent request is a set of one); the service keeps `grantedHosts` per sandbox name (in memory),
  `createHostSetRequest` (leaves out granted hosts, one pending set per sandbox+source),
  `forgetGrants` (new incarnation), approval applies per host via
  `SbxPolicyPort.allowNetwork(sandbox, hosts)` (the seam for the comma form), an approved set
  resolves pending singles of that sandbox, a request for a granted host is answered at once
  (`decidedBy = "goversbx"`, only a `decided` event). F3 guard moved into the domain
  (`LocalTargetGuard`), applied on every path; the MCP tool lost its own copy.
- `sandboxidentity`: `sbx ls` rows carry `workspaces` (`ListedSandbox`); every NEW incarnation is
  announced (`SandboxRegistrationListener`, `RegisteredSandbox`); the admin register endpoint looks
  the name up and announces it too; `findRegistered(name)` for the explicit trigger.
- New context `projecthosts`: `YamlHostSetSource` (jackson-dataformat-yaml, tree model, strict,
  symlink-free, 64 KiB, 100 hosts), `ProjectHostSetService`, admin
  `POST /api/sandboxes/{name}/host-set-requests` (201/200/400/404).
- UI: a set is one row with every host listed, "project host set" badge, source + profiles,
  "Approve all"; notifications only for pending requests; per-host failures shown.
- Tests: unit (YAML source, service, controller, domain, identity events), contract
  (`binaryTest/ProjectHostSetTest`, 14 tests incl. restart and SSE; `FakeSbx.failPolicyFor`), e2e
  (`e2eTest/ProjectHostSetTest` @Order(5): T1 via re-registration, approval -> reachable from `-a`
  only, explicit trigger, and the comma-list verification on `-b`;
  `SbxCliContractTest.policyAllowNetworkTwice...` for idempotency; `ClaudeAgentTest` moved to
  @Order(6); new knob `GOVERSBX_E2E_SET_HOST`, default `example.com`, must be blocked beforehand).
- **Next**: run `./gradlew :daemon:e2eTest` (nested dev sandbox or host). If the comma-list test is
  green, switch `SbxCliPolicyAdapter` to one `sbx policy allow network a,b,c --sandbox` call per set
  and update the spec's decision 3; if the idempotency test fails, treat "already exists" as applied.
  Then open the dashboard once by hand for scenario 14's rendering, bump the version, release.
  Slice 2 (launcher trigger) is next in the epic.

## Spec-driven development; epic 0001 "project host set" (2026-10-23, spec only)

From now on a feature starts as a spec under `docs/specs/` (convention: `docs/specs/README.md`;
epic + shippable slices, Given/When/Then scenarios each tied to a test level). The first epic,
`docs/specs/0001-project-host-set/`, lets a project declare its standard hosts in
`.goversbx/network.yaml` (plus user-supplied profiles in `~/.config/goversbx/profiles/`) so a new
sandbox gets them with one human decision instead of one request per host. Decision: the daemon
resolves the file at sandbox registration and raises one host-set request (slice 1), the launcher
becomes the trigger (slice 2), approved file hashes are pinned for auto-apply (slice 3). Shipped
host lists are deliberately deferred (auto-updating lists are a supply-chain vector, frozen lists
go stale while grants are permanent). **Next**: the user reviews `slice-1.md` and its four open
questions; on `accepted`, implement slice 1 against its scenarios.

## E2E suite + nested sbx dev sandbox unblocked (2026-10-21, inner docker-sbx 0.49.1)

**New: `./gradlew :daemon:e2eTest`** (`daemon/src/e2eTest`, package `run.fabian.daemon.e2e`) - the
end-to-end suite against a REAL sbx, replacing `scripts/e2e-claude.sh` as the primary e2e check (the
bash scripts stay for pi/omp/opencode). Same black-box shape as `binaryTest`, but the daemon runs in
real mode against the real CLI, and the agent side runs *inside* real throwaway sandboxes created
with the Claude kit, through the sbx proxy. One shared fixture (`E2eEnvironment`), classes in
`@Order`:

1. `SbxCliContractTest` - the installed sbx still has the shape the daemon assumes (`ls --format json`
   fields, `secret set-custom` flags, `policy allow network --sandbox`), every kit validates.
2. `ClaudeKitTest` - what `sbx create --kit` installed: daemon reachable via the kit's allow-list,
   `claude mcp list` connected, `~/.claude.json` carries the placeholder header + daemon URL (kit arg
   `daemonPort` applied), skill, `~/.claude/CLAUDE.md` rule, proxy env present.
3. `ApprovalFlowTest` - blocked before; raw placeholder -> "registering" + `set-custom` for both
   sandboxes; pending request under the REAL name; approve -> `sbxApplied`, tool says Approved, host
   200 from `-a` and still blocked from `-b`; `-b`'s request arrives as `-b`; denial leaves the host
   blocked and runs no `policy allow`; `check_network_access_status` polling; wildcard and bare
   `localhost` refused without creating requests.
4. `DaemonRestartTest` - after `stop`+`start` with the same state dir the first call works (no "please
   retry"); with `sandbox-registrations.json` deleted the stale token triggers re-registration and the
   retry works (second `set-custom` in the log).
5. `ClaudeAgentTest` (`@Tag("agent")`, only with `GOVERSBX_E2E_AGENTS=claude`) - real `claude -p`,
   prompt without "goversbx", must create the request itself.

Knobs: `GOVERSBX_E2E_*` (see `E2eConfig`), report `daemon/build/reports/tests/e2eTest`, logs and the
daemon state in `daemon/build/e2e/<run>/`. Sandboxes `goversbx-e2e-<run>-{a,b}` are removed at the
end unless `GOVERSBX_E2E_KEEP=1`; a refused workspace mount falls back to a workspace-less `-a`.

**First real run (2026-10-21, inner sbx 0.49.1, Linux arm64): the whole chain works on 0.49.1** -
kit-created sandboxes WITH a workspace bind mount, kit allow-list reaches the daemon, `claude mcp
list` connected, reactive registration, proxy substitution to the real name, approval ->
`sbx policy allow ... --sandbox` -> HTTP 200 from `-a` only, identity isolation, denial, polling,
refusals, restart durability and recovery. Finding (sbx 0.49.1 behavior, not a daemon bug): **an idle
sandbox - nothing attached, no `sbx exec` in flight - goes to `stopped` within about a minute**, and
`sbx exec` starts it again on demand. So at the first reactive registration `sbx ls` listed only the
sandbox whose call was in flight; the idle `-b` was skipped (correctly: a stopped sandbox cannot be
calling) and got registered on its own first call. The suite's first version expected both to be
registered in one round and `-a` to be "running" at rest; both expectations were relaxed to what sbx
actually does (`lsReportsASandboxAsRunningWhileACommandRunsInIt` pins the part the daemon relies on).

**Third run: green - 27 passed, 1 skipped (`ClaudeAgentTest`, needs Claude credentials in the inner sandbox; `GOVERSBX_E2E_AGENTS=claude` on the host), 7m28s, sandboxes removed.** Second finding (second run): `sbx ls --format json` can
print `WARN: docker hub refresh lock held by another process: context deadline exceeded` - on stderr,
as far as observed (the daemon, which reads stdout only, parsed every `sbx ls` of the run); the suite's
first wrapper merged the streams and tripped over it. `Sbx` now keeps them apart and the contract test
parses stdout alone, so it fails only if sbx ever puts such a warning on stdout - which would break the
daemon's registration too.

### Nested sbx dev sandbox: unblocked (supersedes "Paused" below)

`goversbx-dev` was recreated with `--nested` on sbx 0.49.1. Inside it (this run): `apt-get install
docker-sbx` (0.49.1), `sbx login` (run in the background by the agent, device code relayed to the
user), `sbx policy init balanced`. **The 2026-10-14 "Docker account not enrolled" conclusion was
wrong.** sandboxd's "policy load failed: auth token rejected" / "administrator settings are
unavailable" (which disables `sbx kit validate`, `--kit`, workspace mounts and local allow rules) was
the OUTER sandbox's policy blocking `hub.docker.com` and `api.docker.com` (found via `strings
/usr/bin/sbx`: the token exchange goes through `hub.docker.com/v2/auth/token`). Approving
`login.docker.com`, `api.sandboxes-cloud.docker.com`, `hub.docker.com`, `api.docker.com`,
`auth.docker.com` through goversbx and `sbx daemon restart` fixed all of it: kit validates, per-sandbox
allow rules work, no setting is "unavailable". Inner sandboxes' egress also passes the outer proxy, so
the e2e target hosts (`example.org`, `example.net`) and the image pull hosts (`registry-1.docker.io`,
`auth.docker.io`, `production.cloudfront.docker.com`) had to be approved there too. The Gradle build
in a fresh incarnation needs `services.gradle.org`, `release-assets.githubusercontent.com`,
`plugins.gradle.org`, `plugins-artifacts.gradle.org`, `repo.maven.apache.org`.

## Durable sandbox registrations (2026-10-15, 0.2.8, not yet released)

The user's requirement: losing sandbox registrations on a daemon restart is not acceptable. Before,
the token -> name map and the "already registered" set were in-memory only; every restart (upgrade,
`goversbx stop && start`, reboot) made each sandbox's next call fail with "please retry", cost one
`sbx ls` + `sbx secret set-custom` per sandbox, and an agent that had already used its ~3 retries
gave up. (The 2026-10-14 dogfooding note below claiming "no please-retry after a restart is
expected" was wrong - the secret in sbx survived, the daemon's knowledge of it did not.)

- New driven port `sandboxidentity.application.port.SandboxRegistrationStore` (`load`/`save` of a
  `RegistrationState`: `name@id` incarnations + token -> name) and adapter
  `adapter.persistence.FileSandboxRegistrationStore`: one versioned JSON file,
  `~/.local/state/goversbx/sandbox-registrations.json` (`GOVERSBX_STATE_DIR`, next to
  `admin-token`), owner-only permissions, temp-file + atomic rename, Jackson tree model (no
  reflection on our types, so no new native-image metadata). Unreadable/malformed/other-version
  file -> logged, treated as empty, replaced by the next save.
- `SandboxIdentityService` loads the state in its constructor and saves after every successful
  registration (reactive and admin-endpoint); failed registrations are not saved. `Main` wires the
  file store and logs its path; the convenience constructors (tests) use a no-op store.
- Verified: unit tests for the store (round trip, permissions, missing/corrupt/foreign file, bad
  entries, unwritable path) and the service (restart via a shared store resolves the old token with
  no second `set-custom`; a recreated sandbox is registered again after a restart; explicit
  registration persisted; failure not persisted; lost store falls back to re-registration), and two
  black-box contract tests that stop and restart the real daemon process on the same state dir:
  the restarted daemon logs "Restored 1 sandbox registration(s)", answers the old token's call with
  a pending request under the right sandbox name with zero sbx calls, and a deleted state file
  self-heals via one re-registration. `./gradlew check` (58 unit + 63 contract tests) green.
  `binaryTestNative` could not run in this sandbox (no GraalVM) - the release workflow runs it.
- **Next**: release v0.2.8; restart the host daemon and confirm a registered sandbox's first call
  after the restart creates a request straight away (no "please retry" in the daemon log).

## Native binary contract tests + enforced metadata (2026-10-14, not yet released)

v0.2.0's native binary failed with real Claude Code: `initialize` carries capability objects
(`roots`, nested `elicitation.form/url`) the bash smoke test never sent, so the tracing agent never
saw `McpSchema$ClientCapabilities$RootCapabilities` deserialized. Fixed and made structural:

- `McpSchemaReflectionFeature` (`--features`) registers every `McpSchema` type at native build time.
- `daemon/src/binaryTest`: black-box contract suite (55 tests) against a daemon process + `FakeSbx`
  (scriptable sbx recording exact argv). Admin UI byte-for-byte, admin API incl. 400/404/409, SSE,
  raw MCP (4 protocol versions, both tools, schema validation, sessions, GET stream ended by
  DELETE), a capability matrix generated from the SDK's own records, the MCP Java SDK client,
  replay of traffic **recorded from real Claude Code 2.1.300, pi 1.2.0 and opencode 1.19.0**
  (`client-fixtures/`, `scripts/testing/record-client-fixtures.sh` + `record-mcp-traffic.py`),
  sbx failure injection (ls/set-custom/policy exit 1, malformed JSON, missing binary), 25
  concurrent blocked calls, SIGTERM shutdown. Replaces `daemon-smoke.sh`/`native-metadata.sh`.
- It found a second native-only bug the smoke test couldn't: **every MCP error response was a 500**
  in the binary (Claude Code's `server/discover` probe, session-less requests) - Jackson serializing
  an exception needs `Throwable` reflection.
- **Metadata generation is enforced in Gradle**: `nativeCompile` depends on `nativeMetadata` (the
  suite against the jar under the tracing agent -> `build/native-metadata/`, passed via
  `configurationFileDirectories`). No generated metadata is committed any more. Verified from a
  clean slate: trace 55/55 -> compile -> `binaryTestNative` 55/55 (1m22s); unchanged inputs are
  UP-TO-DATE; `./gradlew check` without GraalVM runs the suite against the jar (25s).
- Found while writing it (behavior pinned, not changed): the MCP SDK validates tool arguments
  against the input schema before the handler (missing `reason`, `timeoutSeconds` 0/121 never reach
  it); the MCP GET stream sends its headers only with the first event or when the session ends.
- `scripts/package-release.sh` (and so the release workflow) runs `check` + `binaryTestNative`.
- Launcher (`scripts/goversbx`): a port held by a process that accepts but never answers is now
  "taken" (with an `lsof` hint) instead of "free"; a repo-checkout daemon runs from a copy of the jar.
- **Next**: release v0.2.1 (the macOS runner runs the same chain); the user's brew install is on the
  broken v0.2.0.

## macOS installer: native binary + Homebrew tap (2026-10-14, v0.2.0 released)

Goal: `brew install` on Apple Silicon, no JVM. Decided with the user: public GitHub repos for the
code and the tap (Homebrew can't download from the private <private-git-host> without a custom download
strategy), arm64 only.

- **Native daemon** (`./gradlew :daemon:nativeCompile`, GraalVM CE 25.0.4 / native-image, plugin
  `org.graalvm.buildtools.native` 1.1.14): 43 MB standalone binary, builds in ~30 s. Metadata from
  the tracing agent over `scripts/testing/daemon-smoke.sh` (new: drives health, the admin UI's
  static files, the admin API, SSE, MCP initialize/tools/list/both tools, reactive registration
  and approval against `fake-sbx`) plus a hand-written `public/**` glob. **Verified here on Linux
  arm64**: all 16 smoke checks pass on the native binary, also with the binary copied alone (the
  `libmanagement_ext.so` native-image emits next to it isn't needed). Found on the way: the SSE
  endpoint only streams for `Accept: text/event-stream` (browsers' EventSource send it; plain curl
  gets an empty 200) - the test sends the header, the daemon is unchanged.
- **Launcher** (`scripts/goversbx`) now has an installed layout: native `goversbx-daemon` next to
  it (or `GOVERSBX_DAEMON_BIN`), kits in `../share/goversbx/kits` (or `GOVERSBX_KITS_DIR`), resolves
  its own symlinks by hand (bash 3.2/macOS), new `version` command; the repo checkout still runs the
  jar. `--goversbx-kit` now lists opencode too.
- **Release tarball** (`scripts/package-release.sh`): native build + smoke test + `goversbx-<v>-
  <os>-<arch>.tar.gz`. Verified the Linux tarball unpacked, through a symlink, with an empty
  `PATH`-ish env: `version`, `start` (native daemon), admin UI 200, `status`, `stop`.
- **Homebrew**: `packaging/homebrew/goversbx.rb.template` (installs into `libexec`/`pkgshare`,
  `bin/goversbx` env script with `opt_` paths, `brew services` launchd service with
  `GOVERSBX_SBX_DRY_RUN=false`, caveats, a `test do` that boots the binary on free ports) and
  `render-formula.sh`. Checked: renders, `ruby -c` OK, rejects a bad sha256. **Not** run through a
  real `brew install` yet - Linuxbrew here needs `ghcr.io`, which couldn't be requested because the
  host's goversbx daemon was down at the time.
- **CI** (`.github/workflows/release.yml`): tag `v*` -> macos-15 runner, setup-graalvm, package +
  smoke test, GitHub release, push rendered formula to `fabian-dot-run/homebrew-goversbx` over SSH
  with a write deploy key on the tap (secret `HOMEBREW_TAP_DEPLOY_KEY`; GitHub's host key pinned,
  not keyscanned). Checked locally against a bare repo: the first release creates `main` in the
  empty tap, a re-run with an unchanged formula is a no-op. Never run on GitHub yet. Public repos:
  `fabian-dot-run/goversbx` (history rewritten before publishing: author `mail@fabian.run`, no
  Docker username or private git host) and `fabian-dot-run/homebrew-goversbx` (empty).
- **Released v0.2.0** (2026-10-14): dry run then tag; macOS arm64 native build + all 16 smoke
  checks green on the runner, GitHub release with tarball + sha256, formula pushed to the tap via
  the deploy key, formula sha256 matches the published tarball. `brew install` worked on the user's
  Mac.
- **First `goversbx run` after the brew install failed** - not the native binary: the user's old
  repo-checkout JVM daemon still held 8787/8788, broken because the jar had been rebuilt under it
  (this sandbox writes to the host checkout; lazy class loading -> `NoClassDefFoundError:
  ManagedSelector$Accept` on every accept). It accepted connections but never answered, and the
  launcher's port check read curl's timeout (exit 28) as "free", started the native daemon, which
  couldn't bind, and showed the old daemon's log spam. Fixed in the launcher (not yet released):
  only curl exit 7 counts as a free port, with an `lsof` hint; a repo-checkout daemon runs from a
  copy of the jar in the state dir, so rebuilds can't break it.

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
- ~~No "please retry" on the first call after a daemon restart is expected: the sandbox-scoped
  identity secret lives in sbx and survives the daemon.~~ Wrong - the daemon's token map didn't
  survive, so every restart did cost a retry. Fixed on 2026-10-15 (durable registrations, see top).

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

## pi, oh-my-pi, opencode: e2e scripts + new opencode kit (2026-10-14)

Extends the automated e2e approach (`scripts/e2e-claude.sh`) to the other three agents, continuing
the backlog item "pi/omp end to end (kits exist, never applied with real sbx)" plus a new opencode
kit. **Not yet run against a real sbx host** (blocked here by the nested-sbx pause above) - mechanics
only, checked against `scripts/testing/fake-sbx` (same smoke-test method the claude kit used before
its first real run) and, for the install steps, for real in this sandbox (not against a real sbx
sandbox, just the bare command).

- `sandbox-kit/kits/goversbx-opencode/` (new): opencode is a **built-in sbx agent**
  (`sbx create --help` lists it alongside claude), so this kit mirrors `goversbx-claude`'s
  install+startup pattern rather than pi/omp's startup-only one. Facts sourced from opencode's own
  docs (`anomalyco/opencode`, `packages/web/src/content/docs/{mcp-servers,config,skills,cli}.mdx`,
  fetched via `gh api`): global MCP config `~/.config/opencode/opencode.json` under `"mcp"`, a
  remote entry needs `"type": "remote"` + `url` (+ `headers`); header values interpolate
  `{env:VAR}` (not pi/omp's `${VAR}`), so the hardcoded placeholder is sent verbatim; skills are
  discovered from `~/.agents/skills/<name>/SKILL.md` among other locations - the same path the pi
  kit already uses, so the kit reuses it (added to `syncKitSkills`'s `kitSkillDirs` in
  `sandbox-kit/build.gradle.kts`); one-shot run is `opencode run "<msg>" --auto` (auto-approves
  permissions not explicitly denied); `opencode mcp list`/`mcp ls` lists configured servers and
  connection status (unlike pi/omp, which have no such subcommand). No Node/Bun guarantee (opencode
  ships as a standalone binary), so the merge script (`register-opencode-mcp.sh`) uses `jq` instead
  of `node -e`, skipping gracefully if `jq` is absent. Verified locally: merges into an empty
  config, preserves an existing unrelated `mcp` entry and other top-level keys, and fails loudly
  (doesn't overwrite) on malformed JSON.
  - **First real run found a schema bug**: `sbx kit validate` failed with `cannot unmarshal !!seq
    into string`. `setup.install[].command` MUST be a **string** (`sh -c <string>`, shell
    metacharacters work) while `setup.startup[].command` MUST be a **list<string>** (exec-style
    argv) - this kit had the pi/omp kits' list form in its `install` entry too (`docker/
    sbx-kits-contrib` `spec/SPEC-v2.md` §5.6, confirmed via `gh api`; the claude kit's install
    commands already used the (correct) string form, which is why only this new kit hit it - pi/omp
    only ever use `startup`). Fixed.
- `scripts/e2e-pi.sh`, `scripts/e2e-omp.sh`, `scripts/e2e-opencode.sh` (new): same shape as
  `e2e-claude.sh` (daemon, two throwaway sandboxes, curl-driven MCP identity/approval flow, 4b
  identity-isolation check), with per-agent differences only in sandbox creation and step 5 (real
  agent). pi/omp have no sbx built-in agent, so both layer their kit onto a `shell` sandbox and the
  script installs the agent itself (not the kit's job) before the kit's startup script can configure
  it. Both default `TARGET_HOST`/`PI_TARGET_HOST` etc. need pre-blocking like `e2e-claude.sh`'s;
  step 5 needs `ANTHROPIC_API_KEY` and auto-skips without it (mirrors `SKIP_CLAUDE`, but opt-out by
  default rather than needing the flag, since a stray CI/dev run is more likely to lack a key than
  to want to skip on purpose).
  - pi: `npm install -g @earendil-works/pi-coding-agent` - confirmed for real in this sandbox
    (`registry.npmjs.org` approved via goversbx): installs cleanly, `pi --version` -> 1.2.0, cleaned
    up afterward. `pi --print "<msg>"` is the one-shot flag (pi docs); MCP tools with
    `exposure: "direct"` (the kit already sets this) are declared to the model regardless of
    `--tools`, so no extra scoping flag is needed for the goversbx tool to be visible.
  - omp: **not** omp's own `curl -fsSL https://omp.sh/install | sh`, even though that's its
    documented default - confirmed for real that its prebuilt-binary fallback resolves a freshly
    signed, randomly-named `*.r2.cloudflarestorage.com` URL on every run (different bucket subdomain
    each time), so there is no stable host a goversbx approval or `sbx policy allow` could ever
    pre-authorize. Confirmed `npm install -g @oh-my-pi/pi-coding-agent` alone doesn't work either:
    it installs, but the package's own `bin` is a `.ts` file (`"bin": {"omp": "src/cli.ts"}`) that
    needs bun to execute - `omp` then fails with `env: 'bun': No such file or directory`. The script
    instead installs bun (`curl -fsSL https://bun.sh/install | bash`, a stable host - confirmed
    working) then `bun install -g @oh-my-pi/pi-coding-agent` (omp's own "Bun (recommended)" method -
    confirmed for real: `omp --version` -> `omp/18.10.4`, and it reads `ANTHROPIC_API_KEY` as
    expected). `omp -p "<msg>"` is the one-shot flag; `yolo` is omp's **default** approval mode
    (auto-approves read/write/exec), so a plain `omp -p` run never blocks on an interactive prompt -
    no extra flag needed. omp has no CLI subcommand to list MCP servers headlessly (`/mcp list` is
    an interactive slash command only), so the script checks `~/.omp/agent/mcp.json` directly
    instead.
  - opencode: no install step (built-in sbx agent, like claude).
- Mechanics confirmed with `scripts/testing/fake-sbx` (all three scripts, `SKIP_PI=1`/`SKIP_OMP=1`/
  `SKIP_OPENCODE=1`): daemon start, kit validate, sandbox create, network reach, the full curl-driven
  identity/registration/approval/policy-allow/reachability flow, and the 4b identity-isolation
  check all pass. For pi/omp this used the real install commands (fake-sbx's `exec` runs for real
  locally, unlike a real sandboxed container) - the smoke test actually installed and then removed
  pi and omp/bun in this dev sandbox each time, which is how the CDN-host finding above was made.
- `./gradlew check` passes (includes `verifyKitSkills` for the new opencode kit).

**First real run (2026-10-14, user's host, sbx):** `scripts/e2e-omp.sh` failed at step 2 twice,
fixed between runs, not yet re-tried for real:
- Run 1: `curl -fsSL https://bun.sh/install | bash` got a plain 403 inside the sandbox - the omp
  kit's `permissions.network.allow` only covers the daemon host, not general internet access, and
  this host's sbx policy doesn't allow it by default either. Fixed: the script now runs `sbx policy
  allow network <host>:443 --sandbox <name>` for the install hosts itself, proactively, right after
  creating each sandbox - the same fallback pattern step 3 already used for daemon reachability.
  Applied to both `e2e-omp.sh` (`bun.sh`) and `e2e-pi.sh` (`registry.npmjs.org`, which a prior host
  run already needed per the note above - now automated instead of a manual prerequisite).
- Run 2 (after that fix): `bun.sh` and `github.com` were now allowed, but bun's own installer
  downloads the actual release asset from `github.com/oven-sh/bun/releases/...`, which 302-redirects
  twice - confirmed with a real `curl -L -D -`: `github.com` -> `release-assets.githubusercontent.com`
  (a signed, time-limited URL, but a **fixed hostname**, same CDN Gradle's wrapper download used
  earlier in this session - unlike omp's own installer's per-release-random R2 subdomain). Added
  `release-assets.githubusercontent.com` to the script's proactive allow-list too.
- Run 3 (after that fix): bun itself installed fine this time, but `bun install -g
  @oh-my-pi/pi-coding-agent` then failed with `GET https://registry.npmjs.org/... - 403` - bun
  fetches the package from the npm registry same as pi's plain `npm install` does, and that host
  was only in `e2e-pi.sh`'s allow-list, not `e2e-omp.sh`'s. Added `registry.npmjs.org` there too.
- Re-verified mechanics with `scripts/testing/fake-sbx` after all three fixes (real bun+omp install,
  cleaned up afterward each time) - passes. `e2e-omp.sh` itself not yet re-confirmed for real on the
  host after the registry.npmjs.org fix.

**`scripts/e2e-opencode.sh` passed end to end on the user's host (2026-10-14)**, after one more real
bug: `sbx kit validate` failed with `cannot unmarshal !!seq into string` - `setup.install[].command`
must be a **string** (`sh -c <string>`), not the list form `setup.startup[].command` requires
(`docker/sbx-kits-contrib` `spec/SPEC-v2.md` §5.6); this kit's `install` entry had copied the
pi/omp kits' list form, which only ever appears under `startup`, so neither of those kits had hit
it. Fixed (string form), confirmed on a real sbx host.

**`scripts/e2e-pi.sh` also passed end to end on the user's host (2026-10-14)**, no further fixes
needed: kit validate, daemon, sandbox create + pi install (both sandboxes), kit's
`permissions.network.allow` reaching the daemon, the full curl-driven identity/registration/
approval/`sbx policy allow`/reachability flow (daemon log shows the real `sbx policy allow network`
output this time, not a dry run), and 4b's identity-isolation check all passed. Step 5 (real `pi
--print`) auto-skipped as designed (`ANTHROPIC_API_KEY` not set).

**Remaining**: re-confirm `scripts/e2e-omp.sh` for real after its `registry.npmjs.org` fix (not yet
independently re-run), then step 5 with `ANTHROPIC_API_KEY` for all three (the actual
autonomous-tool-use proof - the one thing none of these runs have exercised yet).

## Paused: nested sbx dev sandbox (2026-10-14)

**Paused by the user (2026-10-14):** the goal was letting the agent test independently instead of
needing a host run each time - not re-proving the core flow, which is already verified end to end
on the real host (see "MVP verified end to end" and "sandbox-kit is a real sbx kit" above, including
with real Claude Code). Blocked on a Docker Sandboxes account-enrollment issue outside this
repo's/agent's control (see the last bullet below). Not worth pursuing further for now; resume only
if that account issue gets resolved independently. The inner sbx install itself stays in
`goversbx-dev` (KVM, `docker-sbx`, login all still work) in case it's useful later.

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

**Progress (2026-10-14, inside `goversbx-dev`):** steps 1-2 done.
- Step 1: `/dev/kvm` exists, `dmesg` shows no "HYP mode not available" - nested virtualization is
  working (the host's `sbx settings set feature.sandbox-nested` + `--nested` run succeeded).
- Step 2: installed `docker-sbx` 0.48.0 via apt, matching the host's version. `ports.ubuntu.com`
  and `download.docker.com` were blocked by the sandbox's default-deny policy; requested both
  through goversbx (`request_network_access`) and got approved rather than asking the user to run
  `sbx policy allow` by hand. `usermod -aG kvm` from the README wasn't needed/possible: no `kvm`
  group exists on this image, but `/dev/kvm` is already owned `agent:agent` with rw for the owner,
  so the `agent` user has access as-is.
- Step 3: `sbx login` needed a human (device-auth against Docker). The user ran `! sbx login` after
  `login.docker.com` was approved through goversbx; signed in as `<docker-user>`.
- Step 4: built the daemon jar inside `goversbx-dev` (`./gradlew :daemon:shadowJar`) - the jar from
  the host's prior builds was already present (direct git workspace mode), but Gradle itself still
  needed its wrapper distribution and plugin resolution, which chained through several hosts, each
  requested via goversbx: `services.gradle.org` -> redirects through `github.com` ->
  `release-assets.githubusercontent.com` (actual zip); `plugins.gradle.org` -> `plugins-artifacts.gradle.org`
  for the shadow plugin. Build succeeded once all four were approved.
  - The inner sbx's own global network policy was uninitialized (`sbx policy ls` -> "global network
    policy has not been initialized"); ran `sbx policy init balanced` - this only affects sandboxes
    nested inside `goversbx-dev`, not the outer sandbox or the host.
  - `scripts/e2e-claude.sh` picked up the **outer** sandbox's `SANDBOX_NAME=goversbx-dev` env var as
    its default test-sandbox name (collision risk) - must pass an explicit `SANDBOX_NAME=...` when
    running it from inside a goversbx sandbox.
  - **Blocker found:** `sbx kit validate`/`sbx create --kit <local-dir>` fails with `load kit
    policy: read kit.allowedSources: administrator settings are unavailable` (daemon log:
    `error_category":"connection_refused"` talking to a local "administrator settings" backend).
    This is Docker Desktop's admin-console sync component, which doesn't exist in this headless
    Linux CLI-only install - not a goversbx network block, and not something fixable from inside the
    sandbox. **This blocks kit-based testing entirely on the inner sbx** (no local/git/OCI kit can
    be validated or used with `--kit`), so `sandbox-kit/kits/goversbx-claude` can't be exercised
    here; the host remains the only place that can test kits for now.
  - Fallback: plain `sbx create --name <n> claude <workspace>` (no `--kit`) works - this skips kit
    resolution entirely, so it can still exercise the core MCP/identity/approval flow manually
    (like the "Manual MVP test runbook" below), just without the kit's automatic install.
  - Pulling the base image needed two more goversbx approvals: `registry-1.docker.io` /
    `auth.docker.io` (plain 403 "Approval required", not a Docker Hub permission issue despite the
    daemon log saying `registry_access_denied` - that's just how sbx reports a proxy-blocked pull)
    and `production.cloudfront.docker.com` (the CDN host actual registry blobs 307-redirect to -
    `curl -L` against a manually-fetched registry token showed this). After all four, `docker/
    sandbox-templates:claude-code-docker` pulled and a **workspace-less** `sbx create --name N
    claude` (no `--kit`, no path) succeeded end-to-end (image pull, container start).
  - **Second blocker, same root cause:** `sbx create --name N claude <any-path>` (a workspace bind
    mount) fails for every path tried (the outer sandbox's own `/Users/fabian/...` path, and a
    fresh `/home/agent/...` dir inside `goversbx-dev` itself): `403 Forbidden: mount policy denied:
    ... fs:mount:read denied: no applicable policies ... fs:mount:write denied: no applicable
    policies`. No `sbx policy allow fs`/`mount` subcommand exists to grant this locally - filesystem
    mount authorization is evaluated the same way as `kit.allowedSources` (administrator-settings-
    backed), so it's unavailable for the same reason. Only workspace-less sandboxes can be created
    on this inner sbx.
  - **Third blocker, same root cause:** tried the core identity/approval flow manually, without a
    kit, using workspace-less sandboxes and an explicit `sbx policy allow network <host>
    --sandbox <n>` (standing in for the kit's `permissions.network.allow`). That allow itself was
    rejected: `error: allow network rule: network policy for "localhost:9787" is managed by your
    organization; local allow rules are not applied`. Confirmed with a direct `curl -v` from inside
    a nested sandbox to `host.docker.internal:9787`: `403 Blocked by org policy` - the exact phrase
    our own CLAUDE.md says goversbx cannot lift.
  - **Correction after the user confirmed the inner sbx's Docker account (`<docker-user>`) is individual,
    not an org account:** the "managed by your organization" wording is misleading boilerplate, not
    a real org. The daemon's own audit log confirms it (`org_id:"" org_name:""` on every session).
    Traced the actual cause: `strings /usr/bin/sbx` found the policy/governance client talks to
    `https://api.sandboxes-cloud.docker.com`; that host was itself blocked by the **outer**
    sandbox's goversbx policy (`curl` -> "Approval required for api.sandboxes-cloud.docker.com:443"
    - a goversbx block, not sbx's). Requested and got it approved. After that the daemon log's
    `"policy fetch failed"` changed from an opaque "auth token rejected" at the network layer to a
    real `401 Unauthorized` from Docker's own API (confirmed with a direct authenticated `curl`) -
    i.e. the network path is now open, but Docker's governance/policy-snapshot API rejects this
    account's token for that specific endpoint even though plain Docker Hub/registry auth (image
    pulls, `sbx create`) works fine. Restarting the daemon and re-running `sbx login` (which itself
    warned `could not refresh sandbox policies in the running daemon`) didn't change the 401.
    `strings` also turned up a `v1/user_profiles/%s/enrollment_url?beta=true` + "token exchange"
    code path next to the governance error strings, suggesting Docker Sandboxes governance needs a
    separate account enrollment/entitlement step beyond a plain `sbx login` - plausibly one normally
    done through the Docker Desktop GUI, which this headless `apt install docker-sbx` build skips.
  - **Conclusion:** not an org-policy problem and not a goversbx/network problem (fixed that part -
    `api.sandboxes-cloud.docker.com` is now allowed for this sandbox). It's the `<docker-user>` Docker
    account not being authorized against Docker's Sandboxes governance API (`kit.allowedSources`,
    filesystem mount policy, and centrally-managed network policy all read through this one 401'ing
    endpoint) - plain sandbox creation, image pulls and `sbx exec` all work fine without it. KVM,
    nested virtualization, and sbx itself are all confirmed working. **Not fixable from inside the
    sandbox or via goversbx** (the one actual network block in this chain is already lifted).
    Options for whoever resumes this: (a) find out whether `<docker-user>` (or whichever account logs
    into the inner sbx) needs a separate Docker Sandboxes enrollment step done via a browser/Docker
    Desktop; (b) try a different already-enrolled Docker account for the inner `sbx login`; (c) treat
    the nested-sbx idea as validated only up to "can create real workspace-less sandboxes and pull
    real images", and keep testing goversbx's actual mechanics (kits, network policy, workspaces) on
    the host as before.

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

`sandbox-kit/`: sbx v2 mixin kits `kits/goversbx-{claude,pi,omp,opencode}/` (spec.yaml + files/),
each with a copy of the shared skill from `skill/goversbx-network-access/` (`syncKitSkills`; `check`
fails on drift). Details in `AGENTS.md`, "Sandbox kit".

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
3. **Pending requests are in-memory** and lost on restart (an agent then gets "unknown requestId"
   from `check_network_access_status` and should ask again). Sandbox registrations are durable since
   2026-10-15 (`sandbox-registrations.json`).

## Backlog (after the Claude MVP works)

- Find where mixin agent instructions land; optionally a launchd agent so the daemon starts at login.
- Run `scripts/e2e-pi.sh`, `scripts/e2e-omp.sh`, `scripts/e2e-opencode.sh` for real against a real
  sbx host (see "pi, oh-my-pi, opencode" section above - mechanics-only so far).
- Out of MVP scope so far: revocation/expiry of approvals, persistence of pending requests (sandbox
  registrations are persisted; the admin UI is token-gated), audit log, deny reason back to the agent.
