# Handoff: port `goversbx-omp` to sbx kit v3

2026-10-24, host `fabian` (macOS, sbx v0.49.1, signed in as `fkretzer`).
Continues work that started in `~/IdeaProjects/omp-sbx` (extra-kits support) and landed here
because two blockers were proven against a real sbx host.

**Decision taken (user picked option 1):** port `sandbox-kit/kits/goversbx-omp` to
`schemaVersion: "3"` so it composes with other v3 mixins and stops colliding with a host-mounted
`~/.omp`. The other three kits (`claude`, `pi`, `opencode`) stay v2 for now — they are not
affected by the omp-sbx mount, and one kit per migration keeps the e2e surface small.

---

## 1. Two proven blockers (do not re-derive these)

### A. A v2 kit in the composition wipes every v3 mixin's image content

| kit combination (workload `kits/omp` + mixins) | `/opt/omp-sbx/extensions/omlx-provider.ts` |
|---|---|
| omp + omlx | ✓ present |
| omp + omlx + trivial v3 mixin (env + startup only, no content) | ✓ present |
| omp + omlx + v3 mixin **with** dockerfile content | ✓ both files present |
| omp + omlx + `goversbx-omp` (v2), omlx listed first | ✗ **gone** |
| omp + `goversbx-omp` (v2) + omlx, v2 listed first | ✗ **gone** |

Order does not matter. The v2 kit resets the composed image to the workload base; its own
`files/` survive because those are staged by the runtime at container start ("copying N home
files"), the v3 mixin layers do not. Matching string in the sbx binary:

> `mixin composition is accepted in the schema but not yet applied by the runtime`

**Consequence today:** attaching `goversbx-omp` next to the oMLX mixin silently drops the oMLX
model provider. Network policy, env vars and startup hooks still apply — so it fails as "omlx
models are missing in `/model`", far from the cause.

### B. `files/home/.omp/**` cannot coexist with a host-mounted `~/.omp`

omp-sbx passes the host `~/.omp` as an extra workspace (mounted at its mirrored host path and
symlinked to `/home/agent/.omp`). A **v2** kit that ships `files/home/.omp/agent/skills/…` then
fails at create:

```
error: failed to run sandbox container
```

Minimal repro (single file, rc=1, no sandbox left behind):

```bash
mkdir -p /tmp/v7/files/home/.omp/agent/skills/probe
printf -- '---\nname: probe\ndescription: probe\n---\nhi\n' > /tmp/v7/files/home/.omp/agent/skills/probe/SKILL.md
# spec.yaml = goversbx-omp's, with the setup: block replaced by `setup: {}`
sbx create --name v7 <omp-workload-kit> "$PWD" "$HOME/.omp" --kit /tmp/v7     # ✗ fails
# same kit dir with that one file removed                                                      ✓ creates
```

`com.docker.sandbox/agent-skills@1` is **not** the way out: with an absolute path it mounts the
host skills store into the sandbox —

```
Source: /Users/fabian/Library/Application Support/com.docker.sandboxes/sandboxes/agent-skills
Target: /usr/share/sandbox/kit/probe-skills/skills
```

— it does not ship the kit's own skill. It also rejects relative paths:
`capabilities[0].config.path: skills path "./skills" must be absolute and canonical`.

## 2. sbx v3 kit staging semantics (measured, not assumed)

- A v3 kit stages **only its descriptor** to `/usr/share/sandbox/kit/<kit>/kit.yaml` — plus
  `kit.dockerfile` when it has one. Nothing else from the kit directory.
- File content therefore has to come from **the kit's own dockerfile** output. A v2 kit's
  `files/` tree is home-relative and applied by the runtime ("copying 2 home files"); a v3 kit
  has no `files/` channel (`files/opt/…` and `files/home/.cache/…` were both measured to land
  nowhere).
- Two **v3** mixins with content do compose (verified: `/opt/probe-second/probe.txt` and
  `/opt/omp-sbx/extensions/omlx-provider.ts` both present).
- Same-path directory entries are the hazard: the workload creates `/opt/omp-sbx/extensions`
  (`omp.dockerfile:85`, `chown -R agent:agent`); a mixin that re-creates a path the workload
  also creates can replace it. Use paths the workload never names.

## 3. Target layout for the port

```
sandbox-kit/kits/goversbx-omp/
├── goversbx-omp.yaml           # v3 descriptor (schemaVersion "3", kind: mixin) - name comes from the stem
├── goversbx-omp.dockerfile     # stages script + skill into the image
├── .dockerignore               # allow-list: the two files above + stage/**
└── stage/opt/goversbx/
    ├── register-omp-mcp.sh
    └── skills/goversbx-network-access/SKILL.md
```

Measured 2026-10-24 (`sbx kit validate <dir> --offline`, sbx v0.49.1): in v3 the descriptor's
filename is **free** - any root `*.yaml` carrying `schemaVersion: "3"` is picked up (`foo.yaml`,
`kit.yaml`, `goversbx-omp.yaml` all trigger the v3 path), while v2 strictly needs
`spec.yaml`/`spec.yml` (`error: artifact: spec.yaml (or spec.yml) is required`). So the port names
it `goversbx-omp.yaml`, matching the directory stem (how omp-sbx does it: `omp.yaml`,
`omlx-mixin.yaml`) and keeping `--kit-arg goversbx-omp.daemonPort=…` spelled exactly as today.

Rationale for `/opt/goversbx/`: outside `$HOME` (blocker B), outside anything the omp workload
kit names (blocker A's directory-entry hazard), and readable by the agent when `chmod -R a+rX`
is applied in a build stage before `FROM scratch` (the pattern the omp-sbx oMLX mixin uses, see
its dockerfile comment).

`kit.yaml` carries the same four v3 capabilities:

| capability | content |
|---|---|
| `network-policy@1` | `allow: localhost:${{ kit.args.daemonPort }}`, `${{ kit.args.daemonHost }}:${{ kit.args.daemonPort }}` |
| `lifecycle@1` | startup: `sh /opt/goversbx/register-omp-mcp.sh`, user `1000` (numeric — `agent` also works, but the workload kit uses numeric and it keeps the dispatcher output uniform) |
| `agent-context@1` | the `## Network access via goversbx` instructions, unchanged |
| `args` | `daemonHost`, `daemonPort` unchanged, plus `env:` mapping to `GOVERSBX_DAEMON_HOST/_PORT` if v3 keeps that shape — **check**: v2 had `environment.variables`; verify the v3 key before dropping it |

`register-omp-mcp.sh` keeps the mcp.json merge and gains one step: install the skill from
`/opt/goversbx/skills/goversbx-network-access` into `$AGENT_DIR/skills/` at startup. That write
lands on the host `~/.omp` mount whenever the kit runs under omp-sbx — the same already-true
property as the `mcp.json` write, so it is not a new class of side effect, but call it out in
AGENTS.md §"Sandbox kit".

## 4. Steps

1. ~~`docs/specs/`~~ **done 2026-10-24**: `docs/specs/0002-goversbx-omp-kit-v3/` (`epic.md` +
   `slice-1.md`, status `draft`, awaiting acceptance). Scenarios S1-S9 cover the four above plus
   the env mapping, the packaged zip and the no-payload case; the slice also states the two new
   test levels a kit needs (`e2e` via `scripts/e2e-omp.sh`, `build` via gradle +
   `sbx kit validate --offline`, which for v3 can only report "no kit builder configured").
2. Add `goversbx-omp.yaml` + `goversbx-omp.dockerfile` + `.dockerignore`, move the two payload
   files to `stage/`. Delete `spec.yaml` and `files/` **only after** gradle and the e2e script are
   updated.
3. `sandbox-kit/build.gradle.kts` — `kitSkillDirs` maps `goversbx-omp` to
   `files/home/.omp/agent/skills`; point it at `stage/opt/goversbx/skills` so `syncKitSkills`
   and `verifyKitSkills` keep working. Keep the other three entries unchanged.
4. `packageKits`/`package-<kit>` zips: the task comment says "spec.yaml at the root". Verify what
   a v3 zip kit must contain before changing it; if sbx wants `kit.yaml` at the root, the zip
   task needs `from("kits/$kit")` unchanged (the file is already there) and only the comment
   changes.
5. `scripts/e2e-omp.sh` — step 0 (`sbx kit validate`) and step 5 (checks what the kit installed)
   reference the old paths; update both, and add a step that asserts `/opt/goversbx/register-omp-mcp.sh`
   and the omp-sbx oMLX mixin's `/opt/omp-sbx/extensions/omlx-provider.ts` are **both** present in
   one sandbox — that is the regression this port exists to fix.
6. Docs: AGENTS.md §"Sandbox kit" (currently asserts "real sbx **v2 mixin kits**" and
   "The kit ships the skill to `~/.omp/agent/skills/`") + README §"The sbx kits" + the
   "Verification state" paragraph → move `goversbx-omp` from "not been applied with real sbx"
   to verified, with the date.
7. `CHANGELOG.md` — add a line to the upcoming version's section as part of the change
   (repo rule). Version bump per AGENTS.md goes in `build.gradle.kts`, all four
   `kits/*/spec.yaml` and `McpContext` — note the omp kit no longer has a `spec.yaml`, so the
   release checklist in AGENTS.md needs updating at the same time.

## 5. Verification (host, signed-in sbx)

```bash
cd ~/IdeaProjects/goversbx && ./gradlew check && ./gradlew :sandbox-kit:packageKits
scripts/e2e-omp.sh                        # its own PASS/FAIL steps

# the combination that motivated the port:
cd /tmp && mkdir -p probe && cd probe
OMP_KIT=~/IdeaProjects/omp-sbx/kits/omp
OMLX_KIT=~/IdeaProjects/omp-sbx/kits/omlx-mixin
GOV_KIT=~/IdeaProjects/goversbx/sandbox-kit/kits/goversbx-omp
sbx create --name v3probe "$OMP_KIT" "$PWD" "$HOME/.omp" \
  --kit "$OMLX_KIT" --kit "$GOV_KIT" --kit-arg omlx-mixin.port=8000
sbx exec v3probe -- bash -lc '
  ls -la /opt/omp-sbx/extensions/omlx-provider.ts          # must exist (blocker A)
  ls -la /opt/goversbx/register-omp-mcp.sh                  # must exist
  ls -la /home/agent/.omp/agent/skills/goversbx-network-access/SKILL.md   # must exist
  curl -s -o /dev/null -w "%{http_code}\n" --max-time 8 http://host.docker.internal:8787/
  tail -5 /var/log/sbx-kit-startup.log'
sbx rm -f v3probe
```

Note the skill check writes into the host `~/.omp` mount by design; clean up afterwards if the
probe sandbox is not the real working setup.

## 6. Open questions for the user

1. v3 key for kit env vars (`environment.variables` was v2) — confirm against
   `docker/sbx-kits-contrib` `SPEC-v3.md` before dropping the mapping; the register script reads
   `GOVERSBX_DAEMON_HOST/_PORT` and currently gets them from the kit.
2. ~~Keep the kit descriptor filename as `kit.yaml` or keep `spec.yaml`?~~ **Answered by
   measurement**: v3 takes any root `*.yaml` name (the stem is the kit name), v2 needs `spec.yaml`.
   Decided `goversbx-omp.yaml`; `packageKits`' `from("kits/$kit")` is unchanged, only its comment
   and the release checklist's "four `kits/*/spec.yaml`" change.
3. Should `goversbx-pi` be ported in the same change? It ships `files/home/.agents/skills/`
   (not `~/.omp`), so it has blocker A but not B.
4. Whether to report blocker A to Docker as a bug (the runtime string says composition is "not
   yet applied") — the port works around it, but the upstream limitation still bites anyone
   mixing a v2 kit with v3 mixins.

## 7. State of the other repo (`~/IdeaProjects/omp-sbx`)

Uncommitted, tested (`TEST_SKIP_SANDBOX=1 ./test-kit.sh` → 24 passed, 0 failed):

- `omp-sbx` — `--kit` / `--kit-arg` (consumed, not passed to omp), `OMP_SBX_EXTRA_KITS` +
  `OMP_SBX_EXTRA_KIT_ARGS` from `.env` or exported env (exported wins, exported-empty drops),
  `~` expansion, local-reference validation before create, duplicate collapse, kit-args
  last-one-wins per name, mixin drift warning (`not attached` / `still attached` + `--new`),
  `--dry-run` printing the assembled `sbx create`/`sbx run`.
- `sbx-preflight.sh` — `normalize_kit_ref`, `recorded_kits` (JSON first, table fallback),
  `list_contains`, `kit_mixin_drift`.
- `omp-sbxenv` — warns when a mixin is declared, since `sbx env` has no `--kit`.
- `test-kit.sh` — new "launcher kit wiring" section (no sandbox needed) + `TEST_SKIP_SANDBOX=1`.
- `README.md` — new *Extra kits* section, usage block, component table, sbxenv gaps.
- `kits/omlx-mixin/` — **untouched, back at HEAD**. Its dockerfile staging does work; the
  extension only disappears when a v2 kit joins the composition.

Usage from a project once this kit is v3:

```bash
# .env
OMP_SBX_OMLX=1
OMP_SBX_EXTRA_KITS=~/IdeaProjects/goversbx/sandbox-kit/kits/goversbx-omp
omp-sbx --dry-run && omp-sbx --new
```

## 8. Environment facts

- goversbx daemon running: pid in `~/.local/state/goversbx/daemon.pid`, sandbox port **8787**,
  admin **8788**, jar `~/.local/state/goversbx/goversbx-daemon.jar` (0.3.0-dev, built 2026-10-23).
- Installed Homebrew kit dir is **0.2.7** (`/opt/homebrew/share/goversbx/kits/…`) — older than
  the repo's 0.3.0; use the repo path while iterating.
- oMLX running on `127.0.0.1:8000`, serves `Qwen3.8-27B-oQ4e-mtp`, `Qwen3.8-Flash-Next-oQ4e-mtp`;
  no API key configured (`OMLX_API_KEY=proxy-managed`, `SBX_CRED_OMLX_MODE=none`).
- sbx `kit.allowLocalKits=true`, `kit.allowedSources` overridden to `["docker.io/","github.com/docke…"]`.
- All probe sandboxes (`e2e-*`, `omp-proj*`) removed; host `~/.omp/agent/mcp.json` restored
  byte-identical from backup; scratch dirs and probe kits deleted.
- sbx v0.49.2 is available (running 0.49.1) — worth re-running the blocker A matrix after an
  upgrade, since "not yet applied by the runtime" reads like something they intend to finish.
