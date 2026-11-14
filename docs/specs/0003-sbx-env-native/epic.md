# Epic 0003: goversbx on sbx 0.51 - one v3 kit, user defaults, `sbx env`, thin CLI

Status: accepted (2026-11-10, every open question answered by the user) - slice 0 largely measured
(see Evidence); slice 1 (remove project host sets) done, slice 2 (v3 kit + daemon fixes)
implemented and e2e-green on 0.51 in the dev sandbox, awaiting the host run; slice 3 specified (draft, 2026-11-13, measured on 0.53.0); **supersedes epic 0002** (its v3 port becomes
slice 1 here, for every agent at once instead of omp only)

## Problem

sbx moved under goversbx in three releases (0.50.0 2026-10-26, 0.50.1, 0.51.0 2026-11-03;
0.53.0-rc2 is out). Sources: docs.docker.com/ai/sandboxes/release-notes, the environment-file and
kit docs, docker/sandbox-kit-spec. What matters for goversbx:

| # | sbx change | Effect on goversbx today |
|---|---|---|
| 1 | **0.51 breaking:** built-in agents (`claude`, `codex`, `opencode`, `shell`, ...) resolve to published **v3** kits (`docker.io/sbx/<name>:latest`). "V1 and v2 kits cannot be combined with v3 kits." | **All four goversbx kits are v2 → `goversbx run claude …` and `sbx create --kit goversbx-claude claude …` stop working on 0.51.** The e2e suite (`sbx create --kit` with the claude kit) breaks the same way. Not optional any more. |
| 2 | `sbx env` + `~/.sbxenv.yaml` (user defaults) merged beneath the project's `sbxenv.yaml`; lists (`kits`, `mcp.servers`, `ports`) concatenate, scalars override. Plan/approve flow, `postCreate` lifecycle, `--name`, `--force`, `--no-input`. | The natural place for "goversbx everywhere": the goversbx kit as one entry in the user defaults' `kits`, combined with whatever the project (or omp-sbx) declares. Today the launcher hand-rolls `--kit`. |
| 3 | v3 capabilities that are agent-agnostic: `agent-skill@1` (bundled skill exposed in every agent's skills dir, 0.48+), `agent-context@2` (instructions merged into the workload's profile, 0.51), `lifecycle@1` install/startup hooks, `network-policy@1`, `args.<n>.env`. | One kit can serve every agent. Only the MCP registration stays agent-specific, and that is one script. The `~/.claude/CLAUDE.md` marker hack and the four skill copies go away. |
| 4 | `sbx kit add` "installs added image content in all-v3 sandboxes" (0.51); skills, install/startup hooks and allow rules can be added; credentials, volumes, ports, lifecycle `files` and deny rules cannot. Pre-0.51 sandboxes must be recreated before `kit add`. **The use-kits page still says `kit add` can't add mixins to v3 sandboxes** - contradiction, must be measured. | Makes "upgrade an existing sandbox to goversbx" possible without recreation, **if** the goversbx kit stays inside the kit-add-compatible subset. |
| 5 | `sbx secret set-custom --value-stdin` (0.51). | The identity token is currently passed as `--value <token>` (`SbxCliCustomSecretAdapter`), i.e. visible in the host's process list. |
| 6 | 0.51 breaking: custom secrets **without destination hosts** no longer substitute. | goversbx passes `--host localhost` → unaffected; pin it with a contract assertion. |
| 7 | `--no-input` (0.51) prevents interactive prompts (e.g. Docker sign-in) and reports the needed command. | The daemon's `sbx` calls can hang on a prompt today; they should never be interactive. |
| 8 | `sbx ls`/`inspect` JSON now carry the configured bind mounts (workspace, kit, skills) and the creating sbx version. | Basis for "which sandboxes belong to this directory?" (upgrade) and for detecting pre-0.51 sandboxes. |
| 9 | `sbx env` reads `~/.sbxenv.yaml` **only when no path argument is given**; plain `sbx run`/`create` never read it. | The wrapper must call `sbx env run` from the project directory without a path, or the user defaults (and thus goversbx) silently drop out. |
| 10 | MCP servers can be registered **in sbx itself**: `sbx mcp add NAME --url … [--header 'N: v']` (host-global), attached with `--static-mcp` / `sbx mcp load --sandbox`, or declared in an env file's `mcp.servers` (`name`/`url`/`command`/`args`, no headers) - so also in `~/.sbxenv.yaml`. The per-sandbox gateway runs on the **host** and is wired into Claude, Codex, Devin, Gemini, Kiro, OpenCode by sbx itself. | Would replace the agent-specific registration script entirely - **if the daemon can still tell which sandbox calls.** Today that identity is the placeholder the *sandbox proxy* substitutes; gateway→daemon traffic starts on the host and never passes that proxy. See D8. |

## Evidence (measured 2026-11-10, sbx **v0.51.0** in the nested dev sandbox, Linux arm64)

sbx 0.51.0 installed from the release `.deb` (`dpkg -i`; apt only has 0.49.1). Probe kit: a v3 mixin
`goversbx` (descriptor + dockerfile + `stage/usr/share/goversbx/**`) with `network-policy@1`,
`agent-skill@1`, `lifecycle@1` install+startup, `agent-context@1/@2`, args with `env:`; a
header-logging endpoint on port 9787 stood in for the daemon.

| # | Question | Result |
|---|---|---|
| E1 | v2 kit + built-in agent | **Fails as predicted**: `sbx create claude --kit …/goversbx-claude` → `error: resolve kits: cannot mix v3 kits with v1/v2 kits in one sandbox; compositions are single-format (v3: claude; v1/v2: goversbx-claude)`. Today's goversbx is unusable on 0.51. |
| E2 | v3 `goversbx` kit builds and composes with `claude` | **Yes.** `sbx kit validate ./goversbx` builds it (5 s, kit builder works for a local source dir); `sbx create claude --kit ./goversbx --kit-arg goversbx.daemonPort=9787` assembles "kit image from 2 kits" and runs our install hook after the claude kit's. |
| E3 | `agent-skill@1` | **Works**: `~/.claude/skills/goversbx-network-access -> /usr/share/goversbx/skills/goversbx-network-access` (symlink), no copy, no script. |
| E4 | args `env:` | **Works**: `GOVERSBX_DAEMON_HOST`/`_PORT` set in the sandbox and visible to hooks that declare them in `env:`. |
| E5 | `lifecycle@1` install hook → `claude mcp add` | **Works**: `~/.claude.json` has `goversbx-network` (http, placeholder header) next to `mcp-gateway`; startup fallback runs and is idempotent. Logs in `/var/log/sbx-kits/goversbx/install-0.log`. |
| E6 | Identity on the direct path | **Works on 0.51**: after `set-custom --sandbox … --host localhost --placeholder …`, the endpoint receives the substituted token. |
| E7 | Identity through the MCP gateway (`sbx mcp add --url … --header <placeholder>`, `--static-mcp`) | **Still lost on 0.51**: endpoint receives the raw placeholder (same as 0.49.1, see D8). |
| E8 | `command` MCP server: does the host process learn its sandbox? | **No.** Its environment is sandboxd's own (only `PATH`, `PWD` differ), argv is what was registered, MCP `clientInfo` is `docker-sbx 0.1.0`. No identity-safe bridge possible this way. |
| E9 | `agent-context` | **Neither `@1` nor `@2` (alone) puts the text into what Claude always loads.** Both end up as `~/.claude/kits-agent-context/<kit>.md` plus a one-line pointer in the `## Kits` section of `~/.claude/CLAUDE.md` ("read … only when a kit becomes relevant"). That is exactly the weakness the v2 kit's `CLAUDE.md` marker block compensated (Claude followed sbx's own "ask the user to run sbx policy" text). So the marker block stays, as a `lifecycle@1` install hook. |
| E10 | `sbx kit add` onto an existing all-v3 sandbox | **Works on 0.51** (the use-kits page is outdated): container is swapped, a file written to `/home/agent` before survives, skill link, env, MCP registration (install hook ran) and the kit's network rules are all present afterwards. |
| E11 | `kit add` without a terminal | **Refused**: `warning: this change requests permissions beyond what this sandbox was granted … error: stdin is not a terminal; run this command in a terminal to answer the prompt`. No `--force`/`--yes` flag; `--no-input` not offered; pre-granting the same rules with `sbx policy allow network … --sandbox` does **not** suppress the prompt. On a pty, answering `y` to `Approve the new permissions? (y/N)` works. |
| E12 | `~/.sbxenv.yaml` + project `sbxenv.yaml` merge with our kit entry | **Works**: `sbx env plan` lists both files; the user defaults' `kits` entry (`source` + `args.daemonPort`) is applied, the project's `env` too; the created sandbox has the skill and `GOVERSBX_DAEMON_PORT`. With a path argument (`sbx env plan .`) the user defaults are skipped (fact 9 confirmed). |
| E13 | `lifecycle.initialize` in the user defaults (start the daemon) | **Merges and runs**: user-default command first, then the project's, on every `env create` and every `env run` (also for an existing sandbox), with `SBX_SANDBOX_NAME` set. |
| E14 | Approval of host commands | Default (`env.rememberHostCommands=false`): **every** invocation needs approval - a prompt on a terminal, a refusal without one (`… changes things outside the sandbox that have not been approved … --force`). With `env.rememberHostCommands=true`: an earlier interactive approval is remembered, later runs (terminal or not) execute the commands without asking. `--force` approves one invocation; `--skip-host-commands` runs without them. |
| E15 | Kit declaring **both** `agent-context@1` and `@2` + `sbx env run` on the existing sandbox | **Fails**: `error: resolve kits: kit … declares capability groups, competing agent-context versions, or environment references, which resolve only when it is composed with its workload`. Create works, re-run does not. With a single version (`@2` only) re-run works. **The kit must declare exactly one agent-context version.** |
| E16 | Project hosts as a **project kit** (`<workspace>/.sbx/project-net/project-net.yaml`, v3 mixin with only `network-policy@1`, listed under `kits` in the project's `sbxenv.yaml`; no dockerfile needed) | **Works**: after `sbx env create` the listed hosts answer 200 from that sandbox, an unlisted host stays blocked (403 "Approval required"). |
| E17 | Does the env plan show the kit's hosts? | **No.** `sbx env plan` and the approval prompt show only the kit's path under `KITS`; no host appears anywhere. The human approves "this kit", not a host list. |
| E18 | Can the sandbox change the project kit? | **Yes.** `sbxenv.yaml` in the workspace root is read-only inside the sandbox, the kit directory next to it is writable; a host appended from inside was accepted. `sbx env plan` afterwards: "0 to change … already approved"; `sbx env run` applies nothing (the added host stays blocked in the running sandbox). |
| E19 | Agent-added host after `sbx env rm` + `sbx env create` | **Granted.** Create asks for the generic plan approval again (rm clears it; without a terminal it is refused), still without showing hosts - and the host the agent added answers 200. An agent can widen its own next incarnation's network access unseen. |

Consequences: D1 holds with one change (keep the `CLAUDE.md` marker hook, E9). D5's primary path is
`sbx kit add`, run in the user's terminal (E10/E11) - fine for `goversbx upgrade`, which is interactive
anyway. D8 is closed: MCP stays registered in the agent by the kit (E7, E8). Not measured here:
composition with omp-sbx's `omp` + `omlx-mixin` (needs that repo), codex/opencode registration,
pre-0.51 sandboxes in `upgrade`.

## Goal

1. `cd ~/new-project && goversbx run` is the whole setup: daemon up, user defaults checked, a
   `sbxenv.yaml` scaffolded if the directory has none, sandbox created through `sbx env`, agent attached.
2. The goversbx kit lives **once** in `~/.sbxenv.yaml`; every `sbx env` invocation in any project
   picks it up - with or without the `goversbx` CLI.
3. It composes with other v3 kits (omp-sbx's `omp` workload + `omlx-mixin`, Playwright, ...).
4. `goversbx` stays a thin wrapper: its own handful of verbs, everything else is `exec sbx "$@"`.
5. Existing sandboxes of the current directory can be upgraded to goversbx.

## Non-goals

- Changing the daemon's approval model, identity mechanism or MCP tools.
- Supporting sbx < 0.51 with the new kit. The v2 kits stay in the 0.4.x line; 0.6.0 requires 0.51.

## Decision

### D1 - one agent-agnostic v3 mixin `goversbx` (replaces the four v2 kits)

`sandbox-kit/kits/goversbx/goversbx.yaml` (`schemaVersion: "3"`, `kind: mixin`) +
`goversbx.dockerfile` (build stage, `chmod -R a+rX`, `FROM scratch`, everything under
`/usr/share/goversbx/` - a kit-specific prefix, per the spec's collision advice):

- `network-policy@1` runtime allow `localhost:<daemonPort>` and `<daemonHost>:<daemonPort>`.
- `agent-skill@1` → `/usr/share/goversbx/skills/goversbx-network-access`. The runtime exposes it in
  every `agent-skills@1` destination of the composition (claude, codex, omp-sbx's
  `~/.omp/agent/skills`, ...). Replaces four skill copies and `syncKitSkills`' fan-out.
- `agent-context@1` only - **one** version: declaring `@1` and `@2` together breaks `sbx env run` on
  an existing sandbox (E15); `@1` is what the published `claude` kit composes with. It does **not** replace the `~/.claude/CLAUDE.md`
  marker block (E9: both versions only yield a pointer file) - that block stays, as an install
  hook inside `register-mcp.sh`, for Claude only.
- `lifecycle@1` install + startup (idempotent, add-only): `/usr/share/goversbx/register-mcp.sh`
  detects the installed agents and registers the endpoint with the placeholder header for each:
  `claude mcp add`, pi/omp `mcp.json` merge (today's two scripts), opencode (today's script),
  codex (to be measured: header support of `codex mcp add` / `config.toml`). Unknown agent → log
  line, no failure.
- Args `daemonHost`/`daemonPort` with `env: GOVERSBX_DAEMON_HOST/_PORT` (epic 0002 evidence 6).
- **Constraint: nothing outside the `kit add` subset** (no `credential@1`, `volume@1`, `port@1`,
  lifecycle `files`, deny rules) - D5 depends on it, and epic 0004 must respect it too.

Distribution (**decided by the user, 2026-11-10: local only, no Docker Hub**): the kit ships as a
source directory in the Homebrew share / release tarball (`share/goversbx/kits/goversbx`, a repo
checkout uses `sandbox-kit/kits/goversbx`) and `~/.sbxenv.yaml` points at that path. sbx builds it
on the host with its kit builder (E2: 5 s here; the first build on a host also creates the builder).
Kit and daemon come from the same package, so they cannot drift and there is no version to pin.
Accepted costs: no signature verification (sbx has none for source directories - the trust anchor
is the Homebrew package, as for the daemon), `kit.allowLocalKits` must stay enabled, and the kit is
not usable without a goversbx install. The dockerfile must not need a base image pull
(`FROM scratch` + `COPY --chmod`, no build stage) so the build works offline.

### D2 - `goversbx setup`: own exactly one entry in `~/.sbxenv.yaml`

Run explicitly or implicitly by `goversbx run`. Checks and fixes **silently** (decided by the user,
2026-11-10): its own kit entry is repaired without a prompt, with one `goversbx: …` line saying what
changed; the only question it ever asks is the default agent on first use. Checks:

- file exists with `schemaVersion: "1"` (create a minimal one if missing:
  `schemaVersion`, `agent`, `workspace: ${{ env.projectDir }}`, the kit entry);
- **default agent (decided by the user, 2026-11-10)**: it lives in the user defaults as `agent:`.
  If `~/.sbxenv.yaml` has none, the first `goversbx run`/`setup` asks once (amended 2026-11-13: free
  text with examples and default `claude` instead of a list of built-in agents - sbx cannot list them) and writes the answer; never asked again.
  Without a terminal and without `--agent`, `setup` fails with the command to run. A project's
  own `agent:` still overrides it (sbx merge rule);
- `kits` contains **exactly one** goversbx entry - identified by a `source` path ending in
  `/kits/goversbx` - pointing at the kit directory of the running install (a stable path such as
  `$(brew --prefix)/share/goversbx/kits/goversbx`, so `brew upgrade` needs no edit), with
  `args.daemonPort` only when the daemon is not on 8787; a stale path (moved install, repo checkout
  vs. Homebrew) is corrected, a duplicate removed;
- nothing else is touched. Comments and ordering survive (comment-preserving YAML edit; backup to
  `~/.sbxenv.yaml.goversbx-bak` before every write).
- warns if the **project** file lists the goversbx kit too (sbx rejects duplicate kit names in a
  composition) or passes paths in a way that skips the user defaults (fact 9).

`goversbx setup --check` exits non-zero on drift, for `goversbx status` and CI.

Where the YAML editing lives: in the native binary (`goversbx-daemon config …` subcommand, Java,
already has jackson-yaml; needs a comment-preserving node model - snakeyaml-engine), not in bash.
The bash wrapper calls it.

### D3 - `goversbx run` = scaffold + `sbx env`

```
goversbx run [--agent A] [-- ARGS]     # in the project directory
  1. start the daemon if needed                        (today's start_daemon)
  2. goversbx setup --quiet                            (D2; asks only on drift)
  3. no sbxenv.yaml / .sbxenv.yaml here → write one    (D4)
  4. sandbox for this env exists?  name = `sbx env plan` / file `name` / `<agent>-<basename>`
       no  → sbx env create          (sbx shows the plan, user approves)
             POST register                            (pre-warm the identity secret)
       yes → nothing extra
  5. exec sbx env run [-- ARGS]                        (no path argument: fact 9)
```

The old form `goversbx run <agent> <path> [sbx run flags]` and `--goversbx-kit` are **removed at
once** (user decision, 2026-11-10; no deprecation release). `goversbx run` with positional arguments
fails with one line naming the new form and `sbx run …` for the plain sbx behaviour.

### D4 - project scaffold

If the current directory has no `sbxenv.yaml` (amended 2026-11-13: a `.sbxenv.yaml` dotfile in a
project is not read by sbx - slice 3, M2), `goversbx run`/`goversbx
init` writes `sbxenv.yaml` **in the project root** (sbx mounts a root-level env file read-only,
so the agent cannot change the host commands it may later declare):

```yaml
schemaVersion: "1"
agent: claude          # only written when --agent differs from the user default
workspace: .
# kits:               # project mixins; goversbx itself comes from ~/.sbxenv.yaml
#   - docker.io/sbx/playwright:1.61.1
```

`agent:` is normally omitted - the user default applies (D2), so the scaffold is just
`schemaVersion` + `workspace: .` + the commented `kits` hint. No `name` (sbx derives
`<agent>-<basename>`). There is no `.goversbx/network.yaml` any more (D10).

### D5 - `goversbx upgrade [NAME]`: put goversbx onto existing sandboxes

1. Candidates: `sbx ls --format json`, sandboxes whose workspace/mount source equals or contains
   `$PWD` (fact 8); `NAME` narrows it. None → hint to `goversbx run`.
2. Per sandbox, by what `sbx inspect` says:
   - all-v3, created by ≥ 0.51 → `sbx kit add <name> <goversbx ref>` in the user's terminal
     (the permission confirmation is interactive; `kit add` had no `--force` on 0.49.2 - re-measure).
   - pre-0.51 / v1-v2 → `kit add` is refused by sbx. Offer the **exec fallback** (user decision,
     2026-11-10; no recreate):
     `sbx policy allow network localhost:<port> --sandbox <name>`, `sbx cp` the skill and
     `register-mcp.sh` in, `sbx exec <name> sh register-mcp.sh`. Not persistent across a recreate,
     no agent-context; printed as such. Or recreate, with the warning sbx itself gives.
3. Then `POST register`, like `run`.

Measured (E10/E11): `kit add` works on 0.51 and needs a terminal for its permission prompt, so it
is the primary branch and `upgrade` runs it in the foreground.

### D6 - thin CLI

Own verbs only: `run`, `init`, `setup`, `upgrade`, `start`, `stop`, `status`, `logs`, `dashboard`,
`version`, `help`. Every other first argument → `exec sbx "$@"` unchanged (`goversbx ls`,
`goversbx policy log`, `goversbx env rm`, `goversbx kit inspect …`). `goversbx help` prints its own
verbs and then `sbx --help`. Amended 2026-11-13 (slice 3, M9): `run`, `stop`, `setup`, `version` and
`help` are sbx verbs too; goversbx keeps them (user decision) and `goversbx sbx …` is the explicit
way to the sbx verb (`goversbx sbx run …`, `goversbx sbx stop …`).

### D8 - MCP via the sbx gateway (`sbx mcp add`) instead of in-agent registration - decided by the spike

Preferred if it holds: `goversbx setup` runs `sbx mcp add goversbx-network --url
http://127.0.0.1:<port>/mcp --skip-ssrf-check` once and lists it under `mcp.servers` in
`~/.sbxenv.yaml`; the kit loses its `lifecycle@1` registration script and keeps only network rule,
skill and agent-context. Every gateway-wired agent gets the tools; omp-sbx already points omp at
`MCP_GATEWAY_URL`.

Identity is already solved by design, *if* the gateway's upstream call passes the sandbox's proxy:
the registered header carries the **placeholder**, never a name
(`sbx mcp add goversbx-network --url http://127.0.0.1:8787/mcp --header "X-Goversbx-Sandbox-Name:
goversbx-sandbox-identity-placeholder-v1"`, already registered on the host since 2026-10-26), and the
proxy substitutes each sandbox's own sandbox-scoped secret. A sandbox that attaches the registration
in dynamic mode therefore still arrives as itself - no impersonation. The one thing to measure is
whether the substitution happens on the gateway→daemon hop (it starts at
`mcp-gateway.docker.internal`, sandboxd's side, not inside the sandbox).

**Measured 2026-11-10, nested sbx v0.49.1 in the dev sandbox - the gateway path loses identity.**
Setup: a header-logging MCP endpoint on `127.0.0.1:9787`, registered with `sbx mcp add gvprobe
--url http://127.0.0.1:9787/mcp --header "X-Goversbx-Sandbox-Name: goversbx-sandbox-identity-placeholder-v1"
--skip-ssrf-check`, sandbox `sbx create shell --name gvp-a --static-mcp gvprobe`, identity secret set
exactly as the daemon does (`set-custom --sandbox gvp-a --host localhost --env GOVERSBX_SANDBOX_TOKEN
--placeholder <placeholder> --value token-of-gvp-a`), then from inside the sandbox:

| Path | What the endpoint received |
|---|---|
| direct, `curl http://host.docker.internal:9787/mcp` with the placeholder header (after `policy allow localhost:9787`) | `X-Goversbx-Sandbox-Name: token-of-gvp-a` - substituted, as today |
| via the gateway (`$MCP_GATEWAY_URL`, `tools/call whoami`) | `X-Goversbx-Sandbox-Name: goversbx-sandbox-identity-placeholder-v1` - **raw placeholder** |

The gateway's upstream requests come from the host (`127.0.0.1`, `Go-http-client/1.1`,
`clientInfo: docker-sbx`), carry only the registration's static headers plus `Mcp-Session-Id` /
`Mcp-Protocol-Version`, and nothing that names the sandbox. They never pass the sandbox's proxy, so the
substitution that carries goversbx's identity does not happen. With one registration shared by all
sandboxes, the daemon cannot tell callers apart. (The gateway's own server name is
`mcp-gateway-<sandbox>`, but that is only visible to the agent, not upstream.)

Both follow-ups were checked on 0.51.0 the same day: unchanged (E7), and a `command` registration's
host process does not learn its sandbox (E8).

Outcome rule (applied; confirmed on 0.51.0 too, E7/E8 - the `command` server gets no sandbox identity either): gateway path only with an identity the sandbox cannot choose; otherwise the kit keeps
registering the endpoint in the agent (D1) and the gateway stays out.

A different way to get an unforgeable identity through the gateway - one registration per sandbox
with the token as a host-side header secret - is recorded as a spike in
[`../0006-mcp-via-sbx-gateway/epic.md`](../0006-mcp-via-sbx-gateway/epic.md) (2026-11-14, not started).

### D9 - the daemon is not shipped by the kit, and not started from the user defaults

A kit only acts inside the sandbox (Linux image content, hooks in the sandbox); the daemon needs the
host's `sbx` CLI and must stay outside the agent's reach, so it cannot come from the kit. Installation
stays Homebrew/tarball. What sbx does offer is a host command in `~/.sbxenv.yaml`:

```yaml
lifecycle:
  initialize:
    - name: goversbx daemon
      command: goversbx start      # no-op when it is already running
```

Measured (E13/E14): it merges with a project's own lifecycle and runs before every `sbx env create/run`,
so plain `sbx env run` could start the daemon without the wrapper. The price is the approval: by
default sbx asks on every run (and refuses without a terminal), unless `sbx settings set
env.rememberHostCommands true` - a global setting that also remembers approvals of *project* host
commands.

**Decision (user, 2026-11-10): no autostart.** `goversbx setup` writes no `lifecycle` entry and does
not touch `env.rememberHostCommands`. The daemon is started by `brew services start goversbx` or by
the wrapper's own `start_daemon` (`goversbx run` / `goversbx start`). The measurement stays as the
record of why.

### D10 - project host sets are removed (decided by the user, 2026-11-10)

sbx's env file has no network field; the native way to give a project its hosts is a project kit with
`network-policy@1` (E16). Measured against what `.goversbx/network.yaml` (epic 0001) guarantees:

| | project kit in the workspace | project kit outside the workspace | `.goversbx/network.yaml` |
|---|---|---|---|
| per-project hosts | yes | yes | yes |
| human sees the host list before it applies | **no** (E17) | no | yes, in the dashboard |
| agent can extend it for the next incarnation | **yes, unseen** (E18/E19) | no | it can edit the file, but every set is shown and approved |
| extra moving parts | none (sbx only) | env dir beside the project | daemon code, second config file |

A kit inside the workspace is therefore not an equivalent replacement: it turns "the human approves
each host set" into "the agent can grant itself hosts on recreate". A kit outside the mounted
directories is safe but changes the project layout (sbx's recommended `web-app-env/` + `web-app/`).
Global hosts (`sbx policy allow network …`) remain the simple answer for hosts every sandbox may reach.

**Decision: the feature goes.** Nobody uses it yet and its complexity (a bounded context, a second
config file, profiles, a launcher trigger, set rows in the UI) does not pay for the benefit. Epic
0001 is closed as `dropped`; slice 3 (hash-pinned auto-apply) is never specified. goversbx does not
recommend the workspace kit as a substitute (E18/E19) - per-host requests from the agent plus global
policy rules are the model. Removal is its own slice (below): everything epic 0001 added -
`projecthosts` context, `.goversbx/network.yaml` + profiles, `POST …/host-set-requests`, the
multi-host request entity/`RequestOrigin`/`HostSetRequestOutcome`, granted-hosts memory, the
registration listener that existed for it, the dashboard's set row, the launcher's
`host_set_summary`, `jackson-dataformat-yaml` if nothing else needs it, and the unit, contract,
launcher and e2e tests and docs that pin it. The admin `register` endpoint stays (pre-warming). **"Everything" includes the instant approval of a
request for a host already granted to the sandbox** (user decision): such a request is an ordinary
pending request again.

### D7 - small fixes riding along (slice 1)

- `SbxCliCustomSecretAdapter`: `--value-stdin` instead of `--value` (fact 5), `--no-input` on every
  daemon-side sbx call (fact 7).
- Contract test: the identity secret always carries `--host` (fact 6).
- e2e: sandboxes from `claude` + the v3 `goversbx` kit; `exec` exit 125 handled as "no sandbox".
- `docs/specs/0002-*` → `Status: superseded by 0003`.

## Slices

0. **Spike (no release)** on a 0.51 host: v3 `goversbx` kit builds and composes with `claude` and
   with omp-sbx's `omp`+`omlx-mixin` (both files present - epic 0002's regression check);
   `agent-skill@1` lands in claude's and omp's skills dirs; `agent-context@2` reaches what Claude
   always loads; `kit add` onto a 0.51 v3 sandbox (and its confirmation without a TTY); codex MCP
   header support; `~/.sbxenv.yaml` + project file merge with our kit entry; `sbx env create`
   output → sandbox name; **D8**: gateway identity on 0.51 (on 0.49.1 measured: lost, see D8) and the
   env of a `command` server. Results go into this epic's Evidence table.
1. **Remove project host sets** (D10). Pure deletion, `./gradlew check` green, e2e green; can ship
   on sbx 0.49 as it does not depend on the kit work.
2. **v3 kit + daemon fixes** (D1, D7). Kit shipped as a source dir in the tarball/Homebrew share. e2e green on 0.51.
   Release **0.6.0** (requires sbx ≥ 0.51; v2 kits removed).
3. **`setup` + `init` + `run` via `sbx env`** (D2-D4, D6). launcher tests on fake-sbx gain `env`.
4. **`upgrade`** (D5).

## Open questions

None - all answered 2026-11-10 and folded into the decisions above (local kit only, default agent in
the user defaults and asked once, silent repair of the own entry, no network scaffold, no daemon
autostart, project host sets removed entirely, old CLI form removed at once, exec fallback offered).
