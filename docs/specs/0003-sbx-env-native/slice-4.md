# Slice 4: `goversbx upgrade` - goversbx on sandboxes that already exist

Status: implementing (2026-11-13; the user accepted all six proposals and started the
implementation the same day) · Epic: [`epic.md`](epic.md) (D5) · Ships with 0.6.0 if that is still
open when this is done, else 0.6.1

Done: `./gradlew check` is green - `sbx-json` (`unit`, and `contract` against the jar), the
launcher on `fake-sbx` (`launcher`, S3-S10). On real sbx 0.53.0 in the dev sandbox, by hand with the
`shell` workload: `goversbx upgrade` without a terminal (refused by sbx, the hint follows), on a
pseudo-terminal (kit added, daemon started, sandbox registered, daemon reachable from the sandbox on
the non-default port), again ("already has"), and `--refresh` without a terminal.
**Not run: the `e2e` scenarios S11-S14, `upgrade --exec` end to end against a real sbx** (the inner
sbx lost its Docker sign-in before that step; its three parts are M6), and `binaryTestNative`.

## Problem

Since slice 3 the goversbx kit reaches every sandbox that is **created** through `sbx env`. It does
not reach the ones that exist: sandboxes created with plain `sbx run`, created before
`goversbx setup` ran, or created by an sbx older than 0.51. Measured (M5): an `sbx env` sandbox
whose user defaults gain the kit later keeps running without it - `sbx env plan` says the kit
"waits for the next create" and `sbx env run` attaches without adding it. The user's host has
some 55 such sandboxes. Today the only advice is a hand-typed `sbx kit add <name> <kit dir>`, and
for old sandboxes "recreate".

## Scope

Decision D5 of the epic: `goversbx upgrade [NAME]` puts the kit onto existing sandboxes with
`sbx kit add`, and offers an exec-based fallback where sbx refuses that. Plus the hint in
`goversbx run` that slice 3 removed.

### Measured for this slice (2026-11-13, sbx 0.53.0, nested dev sandbox, `shell` workload)

| # | Question | Result |
|---|---|---|
| M1 | What tells goversbx about a sandbox? | `sbx inspect NAME --format json` has `kits` (the workload's digest reference, then each mixin as it was given - our kit appears as its **directory path**), `created_version` (`v0.53.0`), `state`, `sessions`, `workspace`, `host_mounts`. `sbx ls --format json` has `workspaces` per sandbox (host paths, a read-only one suffixed `:ro`; an `sbx env` sandbox also lists its `sbxenv.yaml:ro`). |
| M2 | `sbx kit add` without a terminal | Refused: the kit is built, then `warning: this change requests permissions beyond what this sandbox was granted` / `+ network.runtime.allow: localhost:8787` / `error: stdin is not a terminal; run this command in a terminal to answer the prompt`, exit 1. With `--no-input`: `error: prompts are disabled by --no-input`. No flag approves it (unchanged since 0.51, epic E11). |
| M3 | `sbx kit add` on a terminal | `Approve the new permissions? (y/N)`, then the container is recreated ("swap container"). Works on a **stopped** sandbox (it is started for the swap and left stopped) and takes `--kit-arg goversbx.daemonPort=9001`. A file written to `/home/agent` before survives; the install hook ran (`/var/log/sbx-kits/goversbx/install-0.log`), the env var is set. |
| M4 | Adding the kit a second time | No error and no duplicate: `kits` still lists it once, the container is recreated again, the args of the second call win. **No prompt when the permissions are already granted - then it also works without a terminal** (exit 0). So "add again" is how a kit is refreshed. |
| M5 | An `sbx env` sandbox created before the kit was in `~/.sbxenv.yaml` | `sbx env plan`: `+ <kit> - waits for the next create`. `sbx env run --force` attaches and does **not** add it (`inspect` still shows the workload alone). After `sbx kit add` the plan says `! <kit> - present, not recorded as applied, needs your approval`: the next interactive `sbx env run` asks once more, then it is quiet. |
| M6 | The pieces of the exec fallback | `sbx policy allow network localhost:8787 --sandbox NAME --no-input` adds the rule **without any prompt** (the same rule `kit add` asks about); `sbx cp <dir> NAME:/home/agent/...` copies a directory; `sbx exec -e K=V NAME sh <script>` runs it (and starts a stopped sandbox). The rule disappears with the sandbox. `register-mcp.sh` looks for the skill at the fixed path `/usr/share/goversbx/skills` - outside a kit it has to find it next to itself. |
| M7 | Which sandboxes does sbx refuse `kit add` for, and with which message? | **Measured on the host** (2026-11-13, sbx 0.53.0, `omp-fabench`, created 2026-10-10 by a pre-0.51 sbx from the v2 workload kit `~/IdeaProjects/omp-sbx/sbx-kit`): its `inspect` JSON has **no `created_version`**, `kits` holds that kit's path and `image` is `omp-sbx:latest`. `sbx kit add omp-fabench <kit>` builds our kit and then fails with `error: original kit 0 ("/Users/fabian/IdeaProjects/omp-sbx/sbx-kit"): kit path ... does not exist` - not an age check at all: **`kit add` re-resolves every kit the sandbox was created from**, and that source is gone. So there are at least three ways sbx can refuse (original kit unresolvable, v1/v2 mixed with v3, "created before the recreate feature"), each with its own text. **A built-in workload from before 0.51** (`shell-1`, created 2026-10-25 with the v2 kit `goversbx-omp`): `error: sandbox "shell-1" was created before shell shipped as a kit; recreate it` / `try: sbx rm shell-1 && sbx run shell --kit <its old kit> --kit <ours>`, no prompt, nothing changed. sbx's own advice is to recreate - and its suggested command would fail, because it combines the old v2 kit with v3. |
| M8 | `kit add` on a running sandbox with an attached agent session | **Not measured** (needs an interactive session). The container is swapped, so the session presumably ends. |

## Contract

```
goversbx upgrade [NAME] [--refresh] [--exec]
```

1. **Which sandbox.** With `NAME`: that sandbox, wherever its workspace is. Without: the sandboxes
   whose `workspaces` contain **exactly** the current directory (M1; a `:ro` suffix is ignored).
   - none: `no sandbox has <dir> as its workspace - start one with: goversbx run`, exit 1;
   - one: that one;
   - several: they are listed (name, agent, state, whether they have the kit) with
     `name the one to upgrade: goversbx upgrade <name>`, exit 1. Nothing is upgraded in bulk -
     every upgrade recreates a container.
   - Sandboxes that mount a **parent** of the directory (`claude-IdeaProjects` for
     `~/IdeaProjects/goversbx`) are not candidates; they can be named.
2. **What it has** (`sbx inspect`, M1): a `kits` entry ending in `/kits/goversbx` means goversbx is
   there. If it points at this install's kit directory: `<name> already has the goversbx kit`,
   exit 0, unless `--refresh`. If it points elsewhere (another install, a checkout): treated like
   `--refresh`, with one line saying which path it had.
3. **The upgrade**, in the foreground, on the user's terminal:
   `sbx kit add NAME <kit dir> [--kit-arg goversbx.daemonPort=P]` (the arg only when the daemon is
   not on 8787). sbx shows the permissions it adds and asks (M3); goversbx adds nothing to that
   prompt. Before it, one line each when it applies: `<name> is running with <n> session(s) - the
   upgrade recreates its container` and, without a terminal and not a refresh,
   `sbx will ask to approve the kit's network rules - run this in a terminal`.
   sbx's exit status is goversbx's.
4. **When `kit add` fails**, for whatever reason, sbx's output and exit status are passed on and
   goversbx adds one line: `if sbx cannot add a kit to this sandbox: goversbx upgrade <name>
   --exec (works without the kit; lost on recreate)`. goversbx does not read sbx's error text -
   M7 shows there are several refusals with different wording, and a declined prompt is a failure
   too. **`--exec`** is the explicit second step - the **exec fallback** (epic D5, user decision
   2026-11-10), no `kit add` attempted:
   `sbx policy allow network localhost:P --sandbox NAME`, `sbx cp <kit>/stage/usr/share/goversbx
   NAME:/home/agent/.local/share/goversbx`, `sbx exec -e GOVERSBX_DAEMON_HOST=... -e
   GOVERSBX_DAEMON_PORT=P NAME sh /home/agent/.local/share/goversbx/register-mcp.sh`.
   It prints what this is not: no agent context, gone after a recreate, not shown by
   `sbx inspect` - so `goversbx upgrade` cannot see it later and `goversbx run` keeps hinting.
5. **Then** the daemon is started if needed and the sandbox registered
   (`POST /api/sandboxes/NAME/register`), as `run` does; a failure is one warning.
6. `upgrade` does not run `setup` and does not need the kit in `~/.sbxenv.yaml`. For an `sbx env`
   sandbox it says once: `the next 'sbx env run' will ask to approve the kit it now finds (M5)`.

### `goversbx run` and the sandbox without the kit

When the sandbox of the plan exists (slice 3, step 5 skipped), `run` asks `sbx inspect` once: no
goversbx kit in `kits` -> one line before attaching,
`<name> has no goversbx kit - add it with: goversbx upgrade <name>`. It still attaches.

### Where it lives

Reading sbx's JSON is not a job for `grep`: two helper subcommands of the daemon binary, both
reading stdin and using Jackson's tree model (no reflection, like the registration store):

```
goversbx-daemon sbx-json workspace-sandboxes --dir DIR      < sbx ls --format json
    stdout: NAME<TAB>AGENT<TAB>STATUS per sandbox with DIR among its workspaces
goversbx-daemon sbx-json sandbox --kit-dir DIR              < sbx inspect NAME --format json
    stdout: kit=<this|other:PATH|none> created_version=<v|none> state=<s> sessions=<n>
```

Malformed or unexpected JSON: exit 1, a line on stderr; the launcher then says it cannot tell and
does nothing. `sandbox_exists` in the launcher moves onto the first helper's parser too.

### Kit change

`register-mcp.sh` takes the skill from the directory it lies in when `/usr/share/goversbx/skills`
does not exist (M6). Nothing else in the kit changes; it stays inside the `kit add` subset.

### Not in this slice

- Upgrading several sandboxes in one call; an `--all`.
- Removing goversbx from a sandbox (sbx has no `kit rm`).
- Recreating a sandbox on the user's behalf.
- Updating kits automatically after `brew upgrade` - `goversbx upgrade --refresh <name>` is the way.

## Scenarios

| # | Scenario | Level |
|---|---|---|
| S1 | Given `sbx ls` JSON with sandboxes for this directory, a parent directory, a subdirectory and a `:ro` workspace, then `workspace-sandboxes --dir` prints exactly the ones with the directory itself (also a path with spaces) | `unit`, `contract` |
| S2 | Given inspect JSON with our kit path, another goversbx kit path, no mixin, no `created_version`, then `sandbox` prints `kit=this`, `kit=other:<path>`, `kit=none`, `created_version=none`; given malformed JSON, exit 1 | `unit`, `contract` |
| S3 | Given one sandbox for the directory without the kit, when `goversbx upgrade` runs, then the calls are `ls`, `inspect <name>`, `kit add <name> <kit dir>`, the daemon's `register` - with `--kit-arg goversbx.daemonPort=P` iff the port is not 8787 | `launcher` |
| S4 | Given several sandboxes for the directory, then they are listed with their kit state, exit 1, no `kit add`; with `NAME` only that one is upgraded | `launcher` |
| S5 | Given no sandbox for the directory, then the `goversbx run` hint, exit 1 | `launcher` |
| S6 | Given a sandbox that has this install's kit, then "already has", exit 0, no `kit add`; with `--refresh`, or when its kit path is another install's, `kit add` runs | `launcher` |
| S7 | `goversbx upgrade <name> --exec` runs `policy allow network localhost:P --sandbox`, `cp`, `exec ... register-mcp.sh` in that order, then `register`, and no `kit add`; it prints that this does not survive a recreate | `launcher` |
| S8 | Given `kit add` fails (any reason), then its output and status are passed on, one line names `goversbx upgrade <name> --exec`, nothing else runs - no fallback by itself, no `register` | `launcher` |
| S9 | Given an existing sandbox without the kit, when `goversbx run` runs, then one hint line naming `goversbx upgrade <name>` and `env run` still runs; with the kit, no line | `launcher` |
| S10 | Given `register-mcp.sh` copied to a directory with `skills/` beside it and no `/usr/share/goversbx`, then it registers the endpoint and links that skill | `launcher` (fake home, as slice 2 did by hand) |
| S11 | Given real sbx and a `claude` sandbox created without the kit, when `goversbx upgrade <name>` runs on a pty and the prompt is answered `y`, then `inspect` lists the kit, the skill and `goversbx-network` are there, a file written before survives, and the first `request_network_access` is pending under its own name without "please retry" | `e2e` |
| S12 | Given that sandbox, `goversbx upgrade <name>` again says "already has"; `--refresh` re-adds it without a prompt | `e2e` |
| S13 | Given real sbx and a sandbox without the kit, when the exec fallback runs (`--exec`), then `goversbx-network` is registered with the daemon's URL, the daemon is reachable from the sandbox and the first request is pending under its own name | `e2e` |
| S14 | The installed sbx's `inspect --format json` has `kits` and `created_version`, and `kit add --help` has `--kit-arg` | `e2e` (`SbxCliContractTest`) |

`launcher`: `fake-sbx` gains `inspect`, `kit add` (with `FAKE_SBX_KIT_ADD_FAIL=1`), `cp`
and `workspaces` in its `ls`. `e2e` S11 needs a pseudo-terminal for sbx's prompt: the test wraps
the launcher in `script` (Linux `script -qec`, macOS `script -q /dev/null`).

## Decisions (user, 2026-11-13)

All six proposals accepted as written: (1) only sandboxes whose workspace is exactly the current
directory are candidates, parents can be named; (2) several candidates are listed and nothing is
upgraded; (3) `--refresh` is part of this slice; (4) the exec fallback is the explicit `--exec`,
named in one line after a failed `kit add` - goversbx does not interpret sbx's error; (5) it ships
with 0.6.0 if that is still open, else 0.6.1; (6) attached sessions are one warning line, sbx's own
prompt follows.

## Implementation notes (2026-11-13)

- `sbx-json sandbox` also prints `name=` and `env=yes|no` (an `sbxenv.yaml` among the host mounts);
  `kit_path=` follows `kit=` when a goversbx kit is there.
- `workspace-sandboxes` takes several `--dir`; the launcher passes `$PWD` and `pwd -P`, so a
  directory reached through a symlink (macOS `/tmp`) matches whichever form sbx recorded.
- **`sandbox_exists` stays the `grep` on `sbx ls`** it was (the spec said it would move onto the
  JSON helper): sandbox names cannot contain what would break it, and `run` would pay a second
  process start for nothing.
- The exec fallback is four sbx calls, not three: before `sbx cp` an
  `sbx exec NAME sh -c 'mkdir -p ~/.local/share && rm -rf ~/.local/share/goversbx'` makes the copy
  land in the same place on a second run.
- The line after a failed `kit add` reads `if sbx refused the sandbox itself (too old, its original
  kit gone): goversbx upgrade <name> --exec ...` - without a terminal the failure is usually just
  the unanswered prompt, and the line must not suggest the fallback for that.
- `upgrade` ends with `<name> has goversbx now` after registering.
- The e2e test for S11 types `y` through `script` (util-linux `script -qec`, BSD
  `script -q /dev/null`); the BSD form has not run anywhere yet.

## Open questions

- None for the design. **M8** (what `kit add` does to an attached agent session) is unmeasured; the
  warning line assumes the session ends.
