# Epic 0002: goversbx-omp as a sbx kit v3 mixin

Status: draft (2026-10-24) - awaiting acceptance; no slice started

## Problem

`goversbx-omp` is a **v2** mixin kit (`spec.yaml` + `files/`). It is meant to be layered onto an
omp sandbox together with other mixins - the concrete case is `omp-sbx`
(`~/IdeaProjects/omp-sbx`), which mounts the host's `~/.omp` into the sandbox and attaches an
`omlx-mixin` (kit **v3**) next to ours. Against a real sbx host (v0.49.1) that combination fails
in two independent ways, both proven, both reproducible in minutes (full evidence and a minimal
repro for each: `docs/handoff-goversbx-omp-v3.md` §1, re-checked 2026-10-24):

- **A - a v2 kit in the composition wipes every v3 mixin's image content.** `omp + omlx` has
  `/opt/omp-sbx/extensions/omlx-provider.ts`; `omp + omlx + goversbx-omp` does not, in either
  order. Two v3 mixins with content do compose. The sbx binary carries the string *"mixin
  composition is accepted in the schema but not yet applied by the runtime"*. The failure is
  silent and lands far from its cause: network policy, env and startup hooks of the wiped mixin
  still apply, so the symptom is "the oMLX models are missing in `/model`".
- **B - `files/home/.omp/**` cannot coexist with a host-mounted `~/.omp`.** omp-sbx passes the
  host `~/.omp` as an extra workspace; a v2 kit that ships `files/home/.omp/agent/skills/…` then
  fails at create with `error: failed to run sandbox container`. Our omp kit ships exactly that
  path - so today it cannot be used with omp-sbx at all, mixin composition aside.

Both are properties of the **v2 kit form**, not of goversbx's content. As long as our kit is v2,
every user who composes it with a v3 kit loses that kit's files, and every user who mounts their
host `~/.omp` loses the sandbox entirely.

## Goal

`goversbx-omp` ships as a **v3** mixin kit whose payload lives in image paths that no other kit
and no host mount claims, so that

1. it composes with other v3 mixins without wiping theirs and without being wiped,
2. it applies when `~/.omp` is a host mount,
3. the agent still gets the same two things it gets today: a `goversbx-network` entry in
   `<agentDir>/mcp.json` and the `goversbx-network-access` skill on disk.

## Non-goals

- Porting `goversbx-claude`, `goversbx-pi`, `goversbx-opencode` (deferred, see below).
- Changing what goversbx does: same daemon, same MCP endpoint, same placeholder header, same
  identity model, same skill content.
- Fixing blocker A upstream. Our port works around it; the runtime limitation stays (epic-level
  open question 4).
- Making `sbx kit add` work. It still refuses a kit that declares files, and a v3 mixin that
  ships content still declares content - recreate remains the path.

## Evidence (measured on this host, sbx v0.49.1, 2026-10-24)

Beyond the two blockers, these are the facts the design rests on. Measured, not assumed - and two
of them contradict the handoff's working assumptions:

| # | Fact | How |
|---|---|---|
| 1 | A v3 kit's file content has to come from **its own dockerfile**. A v3 kit stages only its descriptor (and the dockerfile) - `files/opt/…` and `files/home/.cache/…` land nowhere. | sandbox inspection, handoff §2 |
| 2 | Two v3 mixins **with** content compose (`/opt/probe-second/probe.txt` and `/opt/omp-sbx/extensions/omlx-provider.ts` both present). A v2 kit in the mix resets composed image content to the workload base. | blocker-A matrix |
| 3 | Same-path directory entries are the hazard: the omp workload kit creates `/opt/omp-sbx/extensions` itself (`omp.dockerfile:85`, `chown -R agent:agent`); a mixin that re-creates a path the workload names can replace it, owner included. The oMLX mixin sidesteps that by building in a stage and `chmod -R a+rX` before `FROM scratch`. | omp-sbx `kits/omlx-mixin/omlx-mixin.dockerfile` header comment |
| 4 | **Descriptor filename is flexible in v3, strict in v2.** A root `*.yaml` with `schemaVersion: "3"` is picked up under any name (`foo.yaml`, `kit.yaml`, `goversbx-omp.yaml` all trigger the v3 path); a v2 kit must be `spec.yaml`/`spec.yml` (`error: artifact: spec.yaml (or spec.yml) is required`). | `sbx kit validate <dir> --offline` probes |
| 5 | **`sbx kit validate --offline` cannot validate v3**: "kit … is a v3 source kit and this load path has no kit builder configured". v3 schema validation (including unknown fields) only happens through the kit builder sandbox (`docker.io/docker/sbx-kit-builder:1`, created on first source-form build, its engine store is the build cache). | `sbx kit validate --offline` + `sbx kit builder status` (currently "not created") |
| 6 | v3 has **no `environment:` block and no environment capability**. Capability types known to the binary: `sbx@1`, `network-policy@1|@2`, `credential@1`, `lifecycle@1`, `agent-context@1`, `agent-skill@1`, `agent-skills@1`, `agent-sessions@1`, `agent-interactive-sessions@1`, `acp@1`, `host-mount@1`, `volume@1`, `port@1`, `usb-device@1`, `ssh-agent@1`, `git-identity@1`, `resources@1`, `long-running@1`, `privileged@1`, `kit-registry@1`. Env vars reach the sandbox through **`args.<name>.env: VAR`** (the oMLX mixin's `port` arg does exactly that). | `strings` over the sbx binary, cross-checked against `kits/omlx-mixin/omlx-mixin.yaml` |
| 7 | `com.docker.sandbox/agent-skills@1` is not a way to ship our skill: with an absolute path it mounts the **host** skills store into the sandbox, and it rejects relative paths (`capabilities[0].config.path: skills path "./skills" must be absolute and canonical`). | handoff §1 B |

## Options considered

| | Form | Payload | Composes with v3 | Works with host `~/.omp` | Verdict |
|---|---|---|---|---|---|
| A. Stay v2 | `spec.yaml` + `files/home/.omp/…` | runtime home-file copy | ✗ (wipes theirs, A) | ✗ (B) | Rejected: both blockers stand, and they are not ours to fix from inside v2 |
| B. v3 mixin, payload in `/opt/goversbx/`, skill installed at startup | `goversbx-omp.yaml` + `goversbx-omp.dockerfile` | kit image layer | ✓ (2) | ✓ (nothing under `$HOME`) | **Chosen** |
| C. v3 mixin, payload under `~/.omp/agent/skills/` from the dockerfile | v3 | kit image layer at `/home/agent/.omp/…` | ✓ | ✗ - the image layer at a path the host mount shadows is exactly the class of B; and it would bake a sandbox-specific path into the image | Rejected |
| D. `agent-skills@1` capability for the skill | v3 | host skills store | - | - | Rejected (7) |

## Decision

Option B, one kit, one slice:

- **Descriptor** `sandbox-kit/kits/goversbx-omp/goversbx-omp.yaml`, `schemaVersion: "3"`,
  `kind: mixin`, carrying the same four capabilities as today: `network-policy@1`
  (`localhost:<daemonPort>` + `<daemonHost>:<daemonPort>`), `lifecycle@1` (startup
  `sh /opt/goversbx/register-omp-mcp.sh` as `1000`), `agent-context@1` (the unchanged
  "Network access via goversbx" text) and the two args `daemonHost`/`daemonPort`, now with
  `env: GOVERSBX_DAEMON_HOST` / `env: GOVERSBX_DAEMON_PORT` in place of v2's
  `environment.variables` (evidence 6).
- **Content** via `goversbx-omp.dockerfile` into `/opt/goversbx/`: the register script and the
  skill. `/opt/goversbx/` is outside `$HOME` (B) and named by no other kit (3). The image is
  assembled in a build stage and `chmod -R a+rX` before `FROM scratch` - the pattern the oMLX
  mixin proved, so the `agent` user can read the payload.
- **Skill installation moves to runtime**: `register-omp-mcp.sh` copies
  `/opt/goversbx/skills/goversbx-network-access` into `<agentDir>/skills/` on every start, for
  the default profile and for named profiles alike. Under omp-sbx that write lands on the host's
  `~/.omp` - the same already-true property as the `mcp.json` write, and it gets called out in
  AGENTS.md and the skill doc (see open question 3 for the alternative).
- **Naming**: descriptor `goversbx-omp.yaml` (not `kit.yaml`, not `spec.yaml`). Evidence 4 says
  any name works in v3; matching the directory basename keeps `--kit-arg goversbx-omp.<arg>`
  spelling unchanged from today and matches the proven omp-sbx convention (`omp.yaml`,
  `omlx-mixin.yaml`). `packageKits` already zips `kits/<kit>` unchanged, so only its comment
  changes.
- The other three kits stay v2 in this slice: they are not affected by B (none ships `~/.omp`),
  and one kit per migration keeps the e2e surface small. Consequence while that is the state:
  attaching `goversbx-claude`, `goversbx-pi` or `goversbx-opencode` next to a v3 kit still
  triggers blocker A for that v3 kit, so a composition like omp-sbx's has to point at
  `goversbx-omp` and at no other goversbx kit.

## Slices

1. **Slice 1 - port `goversbx-omp` to v3** (`slice-1.md`): descriptor + dockerfile + staged
   payload, gradle `kitSkillDirs`, `scripts/e2e-omp.sh` (including the new both-files-present
   assertion that is the regression this port exists for), docs, CHANGELOG, version bump.
   Ship it as **0.5.0** (user-visible kit format change).
2. Slice 2 (candidate) - `goversbx-pi` to v3: same shape, blocker A applies to it too (it ships
   `files/home/.agents/skills/`), blocker B does not. Its register script and skill install the
   same way; `<agentDir>` is `~/.pi/agent` there.
3. Slice 3 (candidate) - `goversbx-claude` / `goversbx-opencode`. The claude kit is the least
   attractive target: its `install`-time `claude mcp add` and its `CLAUDE.md` marker write-up
   both rely on v2's home-file staging and would need the runtime-install shape as well.

## Deferred, with reasons

- **v2 fallback path.** Keeping `spec.yaml` next to the v3 descriptor so old sbx hosts still
  work: sbx does not pick up a v2 `spec.yaml` in a directory that also declares v3 (evidence 4),
  and v2 + v3 in one directory is precisely the ambiguous state that produced blocker A. One
  format per kit directory.
- **Reporting blocker A to Docker.** Worth doing, but it is a separate artifact (a minimal kit
  pair + the runtime string), not part of this port. Tracked as open question 4.
- **Re-running the blocker-A matrix on sbx v0.49.2.** "Not yet applied by the runtime" reads like
  something upstream intends to finish; if 0.49.2 composes v2 and v3, slice 1 stays valid but the
  urgency of the other ports drops. Do it on the next host maintenance, not inside this slice.

## Open questions (for the user, before acceptance)

1. **v3 env mapping.** Evidence 6 says `args.<name>.env`. The slice pins a scenario that reads
   `GOVERSBX_DAEMON_HOST`/`_PORT` back inside a real sandbox - accepted as the only mechanism, or
   do we keep the script's defaults (`host.docker.internal` / `8787`) as the source of truth and
   drop the env mapping entirely?
2. **Descriptor name.** `goversbx-omp.yaml` as decided above, or `kit.yaml`? Only the release
   checklist and `packageKits`' comment care; `--kit-arg` spelling resolves to `goversbx-omp`
   either way (directory basename == descriptor stem).
3. **Skill write onto the host's `~/.omp`.** omp-sbx mounts the host's real `~/.omp`, so the
   startup skill install (and the existing `mcp.json` merge) modify the host config and stay there
   after the sandbox is gone. Options: (a) accept it and document it, (b) have the script skip the
   skill copy when `<agentDir>` is a mountpoint, (c) set `OMP_AGENT_DIR` to a sandbox-local path
   in the kit's lifecycle env. (a) is what this spec assumes - it is the least surprising for a
   kit whose whole job is to configure omp.
4. **Blocker A upstream report**: yes/no, and if yes, who files it (us, with the kit pair from
   `docs/handoff-goversbx-omp-v3.md` §1).
5. **Version**: 0.5.0 for slice 1 (kit format is user-visible), or fold it into the next release
   that has features?
