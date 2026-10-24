# Slice 1: `goversbx-omp` as a v3 mixin kit

Status: draft (2026-10-24) - awaiting acceptance · Epic: [`epic.md`](epic.md) · Ships in: 0.5.0 (open question 5)

One kit, one format change, no daemon change. Deliverable: `sandbox-kit/kits/goversbx-omp/` is a
v3 mixin kit that composes with the omp-sbx oMLX mixin and survives a host-mounted `~/.omp`,
with the agent-visible behaviour unchanged.

## Scope

In: `sandbox-kit/kits/goversbx-omp/**`, `sandbox-kit/build.gradle.kts`, `scripts/e2e-omp.sh`,
`AGENTS.md`, `README.md`, `CHANGELOG.md`, `docs/specs/0002-…`.
Out: the other three kits, `daemon/**`, `scripts/goversbx`, the omp-sbx repo (its extra-kits
support is already written and tested there, uncommitted).

## Target layout

```
sandbox-kit/kits/goversbx-omp/
├── goversbx-omp.yaml          # v3 descriptor; replaces spec.yaml
├── goversbx-omp.dockerfile    # stages stage/opt/goversbx/** into the image
├── .dockerignore              # allow-list: the two files above + stage/**
└── stage/opt/goversbx/
    ├── register-omp-mcp.sh                       # from files/home/.local/share/goversbx/
    └── skills/goversbx-network-access/SKILL.md  # from files/home/.omp/agent/skills/ (synced copy)
```

`spec.yaml` and `files/` are deleted **after** gradle and `scripts/e2e-omp.sh` are switched over
(epic evidence 4: leaving a v2 `spec.yaml` in a v3 directory is how blocker A-shaped ambiguity
gets reintroduced).

## Contract

### Descriptor (`goversbx-omp.yaml`)

| v2 (today) | v3 (after) |
|---|---|
| `schemaVersion: "2"`, `name: goversbx-omp`, `version: "0.4.0"` | `# syntax=docker/sandbox-kit:3`, `schemaVersion: "3"`, `kind: mixin`; no `name` (the file stem is the kit name - same as `omlx-mixin.yaml`), no `version` (open question 5 decides whether 0.5.0 is carried in the descriptor at all) |
| `displayName`, `description` | unchanged, plus `sourceUrl: https://github.com/fabian-dot-run/goversbx` |
| `args.daemonHost` / `args.daemonPort` with `default` + `pattern` | unchanged, **plus** `env: GOVERSBX_DAEMON_HOST` / `env: GOVERSBX_DAEMON_PORT` |
| `permissions.network.allow` | `capabilities[]` `com.docker.sandbox/network-policy@1` → `config.runtime.allow: ["localhost:${{ kit.args.daemonPort }}", "${{ kit.args.daemonHost }}:${{ kit.args.daemonPort }}"]` (both forms, the proxy matches the daemon as `localhost`) |
| `environment.variables` | gone - v3 has no such block (epic evidence 6); the two vars come from the args' `env:` |
| `setup.startup[].command: ["sh","/home/agent/.local/share/goversbx/register-omp-mcp.sh"]`, `user: "agent"` | `capabilities[]` `com.docker.sandbox/lifecycle@1` → `config.startup[]` `command: ["sh","/opt/goversbx/register-omp-mcp.sh"]`, `user: "1000"` (numeric, as the omp workload kit uses; `agent` also resolves) |
| `agentInstructions.content` | `capabilities[]` `com.docker.sandbox/agent-context@1` → `config.content`, text unchanged. Still a pointer under `## Kits`, not agent memory - the skill stays the carrier (AGENTS.md §"Sandbox kit", claude bullet) |

### Dockerfile

Two stages, the pattern omp-sbx proved with `omlx-mixin.dockerfile`: build in a real stage,
`chmod -R a+rX`, then copy out of `scratch`, so the kit ships no fresh directory entry for
`/opt/goversbx` (epic evidence 3). `register-omp-mcp.sh` additionally gets `755` (Zip task
aside, the image must carry the executable bit).

### `register-omp-mcp.sh`

Keeps everything it does today (profile resolution, node-or-bun, fail-on-malformed, skip with
neither, the placeholder literal). One addition, because the skill no longer arrives as a
home file: it installs the skill from the image into the resolved `<agentDir>`.

```
SKILL_SRC=/opt/goversbx/skills/goversbx-network-access    # from the kit's dockerfile
# after the mcp.json merge:
if [ -d "$SKILL_SRC" ]; then
  mkdir -p "$AGENT_DIR/skills"
  rm -rf "$AGENT_DIR/skills/goversbx-network-access"
  cp -R "$SKILL_SRC" "$AGENT_DIR/skills/"
else
  echo "goversbx: $SKILL_SRC not found - skill not installed" >&2   # warn, still exit 0
fi
```

This **replaces** the script's current profile-copy block (which copies from the default profile's
skills dir, populated there by v2's `files/`). With the payload in `/opt` the source is identical
for every profile - default included - so the `!= "$DEFAULT_SKILL_DIR"` guard and
`DEFAULT_SKILL_DIR` itself go away; the script keeps `set -e`, hence the `if` around a source that
may legitimately be absent.

Header comment gets rewritten to match reality: v3 kit, `/opt/goversbx/` payload, and the
explicit note that under omp-sbx `~/.omp` **is the host's directory**, so both the `mcp.json`
merge and the skill copy persist on the host after the sandbox is gone (open question 3, decided:
document it).

### Gradle (`sandbox-kit/build.gradle.kts`)

- `kitSkillDirs["goversbx-omp"]` → `"stage/opt/goversbx/skills"` (so `syncKitSkills` and
  `verifyKitSkills` keep working; the other three entries unchanged).
- `packageKits` description: "spec.yaml at the root" → "the kit descriptor at the root"; the Zip
  task's `from("kits/$kit")` needs no change - it already takes the whole directory.
- Header comment: "kits/<name>/ are real sbx v2 mixin kits" → v2 except `goversbx-omp` (v3).

### `scripts/e2e-omp.sh`

- **step 0**: `sbx kit validate` stays, but for v3 it is no longer offline-cheap - it builds
  through the `sbx-kit-builder` sandbox, which is created on first use on this host (epic
  evidence 5). Preflight gains a `sbx kit builder status` info line and a note that the first run
  pulls `docker.io/docker/sbx-kit-builder:1`.
- **step 5**: the two kit-content checks change path - script at `/opt/goversbx/register-omp-mcp.sh`,
  skill at `/home/agent/.omp/agent/skills/goversbx-network-access/SKILL.md` (installed by the
  startup hook, not by `files/`), `mcp.json` check unchanged.
- **new step 5a** (the regression this port exists for): when `OMP_SBX_DIR` (default
  `~/IdeaProjects/omp-sbx`) exists, create one sandbox from the omp **workload** kit plus the
  oMLX mixin plus our kit, and assert **both** `/opt/omp-sbx/extensions/omlx-provider.ts` and
  `/opt/goversbx/register-omp-mcp.sh` exist, in both mixin orders; skipped with a reason when the
  directory is absent. Same sandbox is created with `"$HOME/.omp"` as an extra workspace, which is
  blocker B's repro turned into a passing check.
- New knobs documented in the header: `OMP_SBX_DIR=…`, `SKIP_COMPOSITION=1`.

### Docs

`AGENTS.md` §"Sandbox kit" (v2→"v2, except goversbx-omp (v3)"; the `goversbx-omp` bullet
rewritten to the `/opt/goversbx/` + runtime-skill-install shape, host-`~/.omp` side effect named),
§Releasing (the version checklist's "four `sandbox-kit/kits/*/spec.yaml`" → three, plus whatever
the descriptor carries if open question 5 keeps a version there), "Verification state" (omp kit
moves to verified, with date + sbx version); `README.md` §"The sbx kits" (the `--kit` composition
example, `sbx kit add` still refused); `CHANGELOG.md` 0.5.0 line.

## Scenarios

Levels: `e2e` = real sbx host via `scripts/e2e-omp.sh`; `build` = `./gradlew check` /
`packageKits` / `sbx kit validate` (offline where that works). The three levels in
`docs/specs/README.md` are JVM-side; a kit is pinned at these two, which this slice states
explicitly rather than leaving the scenarios unlevelled.

| # | Given / When / Then | Level |
|---|---|---|
| S1 | Given a sandbox from the omp workload kit + oMLX mixin + our kit, when it is created, then `/opt/goversbx/register-omp-mcp.sh` **and** `/opt/omp-sbx/extensions/omlx-provider.ts` both exist - in both mixin orders (blocker A) | `e2e` |
| S2 | Given `~/.omp` as an extra workspace, when `sbx create --kit …` runs, then create succeeds and the sandbox reaches `http://host.docker.internal:<port>/api/health` (blocker B) | `e2e` |
| S3 | Given a sandbox with our kit, when startup hooks have run, then `<agentDir>/mcp.json` has the `goversbx-network` entry with the placeholder header **and** `<agentDir>/skills/goversbx-network-access/SKILL.md` exists | `e2e` |
| S4 | Given `GOVERSBX_DAEMON_PORT=9999` (via `--kit-arg goversbx-omp.daemonPort=9999`), when the sandbox starts, then the startup hook registers `http://host.docker.internal:9999/mcp` - i.e. the v3 `args.env` mapping really delivers the var (open question 1) | `e2e` |
| S5 | Given `OMP_PROFILE=dev` (so `<agentDir>` is `~/.omp/profiles/dev/agent`), when the startup hook runs, then the skill lands in that profile's skills dir | `e2e` (or `build` against a fake root, like the existing script checks) |
| S6 | Given a running sandbox without our kit, when `sbx kit add` gets it, then the outcome is **recorded**, not assumed: today's v2 kits are refused because they declare `files`; a v3 kit that ships content through its image may behave differently. Either way the sandbox must not end up half-configured - if `add` succeeds, the script and the skill still have to be missing until recreate | `e2e` |
| S7 | Given the skill source, when `./gradlew :sandbox-kit:check` runs after editing `sandbox-kit/skill/`, then `verifyKitSkills` fails until `syncKitSkills` refreshed `stage/opt/goversbx/skills/…` | `build` |
| S8 | Given `./gradlew :sandbox-kit:packageKits`, when `sbx kit validate build/distributions/goversbx-omp.zip` runs, then the zip validates as a v3 kit (descriptor at the root); with `--offline` the expected result is sbx's "v3 source kit … no kit builder configured" error, which is a limit of the offline path (epic evidence 5) and not a failure of the kit. **Unverified so far** - no v3 kit here has been through `kit validate` or through the builder (not created yet), so step 0 of the first e2e run is also the first real schema check of the descriptor and is where an unsupported key (`version`, `env`, `pattern`) surfaces | `build` |
| S9 | Given the script run outside the kit (no `/opt/goversbx/skills/…`), when it runs, then it warns on stderr, still writes `mcp.json`, and exits 0 | `build` (host, `node`/`bun` against a temp `$HOME`) |

## Verification block

```bash
cd ~/IdeaProjects/goversbx
./gradlew :sandbox-kit:check :sandbox-kit:packageKits
scripts/e2e-omp.sh                    # its own PASS/FAIL steps, incl. new 5a

# S1 + S2 + S4 by hand, the exact combination that motivated the port:
cd /tmp && mkdir -p probe && cd probe
sbx create --name v3probe ~/IdeaProjects/omp-sbx/kits/omp "$PWD" "$HOME/.omp" \
  --kit ~/IdeaProjects/omp-sbx/kits/omlx-mixin \
  --kit ~/IdeaProjects/goversbx/sandbox-kit/kits/goversbx-omp \
  --kit-arg omlx-mixin.port=8000 --kit-arg goversbx-omp.daemonPort=8787
sbx exec v3probe -- bash -lc '
  ls -la /opt/omp-sbx/extensions/omlx-provider.ts
  ls -la /opt/goversbx/register-omp-mcp.sh
  ls -la /home/agent/.omp/agent/skills/goversbx-network-access/SKILL.md
  curl -s -o /dev/null -w "%{http_code}\n" --max-time 8 http://host.docker.internal:8787/api/health
  tail -5 /var/log/sbx-kit-startup.log'
sbx rm -f v3probe
```

The skill check writes into the host's `~/.omp` by design; clean up afterwards if this is not the
working setup (`rm -rf ~/.omp/agent/skills/goversbx-network-access`, restore `mcp.json`).

## Rollback

`git revert` the slice: v2 `spec.yaml` + `files/` come back with it. Nothing in `daemon/` changed,
so no data or state migration is involved; the host keeps whatever `mcp.json`/skill the last run
wrote into `~/.omp`.

## Open questions carried from the epic

1. env mapping (`args.<name>.env`) - decided here as "keep", S4 pins it; if the real run shows
   the var never arrives, drop `env:` and read the args through the startup command instead
   (`command: ["sh","-c","GOVERSBX_DAEMON_PORT=<port> sh /opt/goversbx/register-omp-mcp.sh"]`).
2. descriptor name - decided `goversbx-omp.yaml`; `kit.yaml` is equally valid to sbx (epic
   evidence 4), the argument is consistency with the directory and with `omp.yaml`/`omlx-mixin.yaml`.
3. skill onto the host's `~/.omp` - decided: accept, document (AGENTS.md + script header + README).
5. version in the descriptor - if v3 has no version slot, the kit's version comes from
   `build.gradle.kts`/CHANGELOG and the release checklist loses the omp entry.
