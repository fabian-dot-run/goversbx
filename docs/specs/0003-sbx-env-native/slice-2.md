# Slice 2: one v3 kit for every agent, daemon fixes for sbx 0.51

Status: implementing (2026-11-10). `./gradlew check` is green (unit, contract, launcher, kit skill
drift); the kit was built and applied on real sbx 0.51.0 for `claude`, `opencode` and `shell`;
`:daemon:e2eTest` is green on sbx 0.51.0 in the nested dev sandbox (Linux arm64, 5m58s, every class
passed, `ClaudeAgentTest` skipped as usual). `done` once the user has run the suite on the macOS
host (first kit build there creates sbx's kit builder) - and `binaryTestNative` has run, because the
stdin write to the sbx process is a new code path for the native binary.

## Problem

sbx 0.51 resolves the built-in agents to published v3 kits and refuses to combine them with v1/v2
kits (`cannot mix v3 kits with v1/v2 kits in one sandbox`, epic E1). goversbx's four kits are v2, so
`goversbx run claude …`, plain `sbx create --kit goversbx-claude claude …` and the e2e suite all fail
on 0.51. Separately, the daemon passes the identity token to `sbx secret set-custom` as `--value`,
where every process of the host user can read it, and its sbx calls can wait on a prompt.

## Scope

Decisions D1 and D7 of the epic. The CLI's new shape (`setup`, `init`, `run` via `sbx env`,
`upgrade`) is slices 3 and 4; in this slice `goversbx run [sbx run args…]` keeps today's form and
only switches to the one kit.

### Added / changed

- `sandbox-kit/kits/goversbx/`: `goversbx.yaml` (`schemaVersion: "3"`, `kind: mixin`),
  `goversbx.dockerfile` (`FROM scratch` + `COPY --chmod=0755 stage/ /`, no base image),
  `.dockerignore`, `stage/usr/share/goversbx/register-mcp.sh` and the synced skill copy under
  `stage/usr/share/goversbx/skills/`.
  - Capabilities: `network-policy@1` (daemon host/port), `agent-skill@1` (optional),
    `lifecycle@1` install + startup running `register-mcp.sh`, `agent-context@1` (one version only,
    E15). Args `daemonHost`/`daemonPort` with `env:`. Nothing outside the `sbx kit add` subset.
  - `register-mcp.sh`: one script for Claude Code, opencode, pi and oh-my-pi; registers for the
    agents it finds, links the skill where the runtime does not, writes the marker block into
    `~/.claude/CLAUDE.md` only when its content differs, never overwrites a malformed config,
    exits 1 if an agent's step failed.
- The four v2 kits are deleted. `sandbox-kit/build.gradle.kts` knows one kit; `syncKitSkills` /
  `verifyKitSkills` / `packageKits` work on it.
- `scripts/goversbx`: uses `<kits>/goversbx`, `--kit-arg goversbx.daemonPort=…`; `--goversbx-kit`
  and `GOVERSBX_KIT` are removed (the flag is refused with one line); for an existing sandbox it
  prints the `sbx kit add` command instead of "recreate".
- Daemon: `SbxCliCustomSecretAdapter` uses `--value-stdin` (token written to the process's stdin);
  every sbx call (`ls`, `policy allow network`, `secret set-custom`) carries `--no-input`. The
  dry-run output shows the would-be stdin value as `<stdin:…>` (nothing runs in dry-run, so that is
  the only place to read it).
- e2e sources: sandboxes are created as `claude` + the `goversbx` kit; `SbxCliContractTest` checks
  `--value-stdin`/`--no-input` in the CLI help and validates the one kit.
- `scripts/e2e-{claude,pi,omp,opencode}.sh`: kit path and kit-arg name adapted; pi/omp run
  `register-mcp.sh` once after installing the agent (it was installed after create).

### Not in this slice

- Codex registration (header support of `codex mcp add` not measured).
- Version bump and release (0.6.0 comes with slice 3, when the CLI matches the README's quick start).

## Scenarios

- S1 **(e2e)** Given sbx 0.51 and the `goversbx` kit, when a sandbox is created with
  `sbx create claude --kit <kit>`, then `claude mcp list` shows `goversbx-network` connected,
  `~/.claude.json` carries the placeholder header and the daemon URL built from `daemonPort`, the
  skill is at `~/.claude/skills/goversbx-network-access/SKILL.md`, and `~/.claude/CLAUDE.md` has the
  goversbx block. (`ClaudeKitTest`)
- S2 **(e2e)** Given such a sandbox, when the agent side calls the endpoint with the raw
  placeholder, then the daemon registers it with `set-custom --value-stdin`, the retry arrives under
  the sandbox's real name, and approval makes the host reachable from that sandbox only.
  (`ApprovalFlowTest`)
- S3 **(e2e)** The installed sbx documents `--value-stdin` and `--no-input`, and
  `sbx kit validate <kit>` succeeds. (`SbxCliContractTest`)
- S4 **(contract)** When the daemon registers a sandbox, the recorded sbx call is
  `secret set-custom --sandbox N --host localhost --env … --placeholder … --value-stdin --no-input`
  and the token was read from stdin - it is not in the argv. (`SandboxIdentityTest`)
- S5 **(contract)** `policy allow network H --sandbox N --no-input` and `ls <format args>
  --no-input` are the exact calls. (`AdminApiTest`, `McpProtocolTest`, `SandboxIdentityTest`)
- S6 **(launcher)** `goversbx run --name n1 claude <ws>` calls `sbx create --kit <kits>/goversbx …`,
  registers, attaches. (`test-launcher.sh` check 1)
- S7 **(manual, sbx 0.51, recorded in the epic's Evidence)** the kit composes with `opencode`
  (entry merged into `opencode.json`, skill linked into `~/.agents/skills`) and with `shell` (no
  agent: nothing registered, create succeeds); `sbx kit add` puts it onto an existing claude sandbox.

## Open questions

- pi and omp against the v3 kit on a real host (the bash e2e scripts were adapted, not rerun), and
  the composition with omp-sbx's `omp` + `omlx-mixin` - both need the host.
