# Slice 3: `goversbx setup`, `init` and `run` on `sbx env`, thin CLI

Status: implementing (2026-11-13; the user answered questions 1-5 and started the implementation
the same day) · Epic: [`epic.md`](epic.md) (D2, D3, D4, D6) · Ships as: **0.6.0** (with slices 1
and 2)

Done: `./gradlew check` is green - the editor and the `config` command (`unit`), the `config`
subcommand as a process against the jar (`contract`), and the launcher on `fake-sbx` (`launcher`).
**Not run: the three `e2e` scenarios (S23-S25) and `binaryTestNative` (S10)** - no sandbox could be
created in time on the day (downlink of a few KB/s) and the dev sandbox has no GraalVM. `done`
once those have run, here or on the host.

## Problem

After slice 2 the kit is one v3 mixin, but the launcher still hand-rolls
`sbx create --kit <dir> <agent> <path>`. That has three costs: the kit only reaches sandboxes started
through `goversbx run`; it does not compose with what a project declares in its own `sbxenv.yaml`
(omp-sbx, Playwright, ...); and the launcher has to understand sbx's `run` arguments to find the
sandbox name. sbx's own answer is `sbx env` with user defaults in `~/.sbxenv.yaml`.

## Scope

Decisions D2 (setup), D3 (run), D4 (scaffold) and D6 (thin CLI) of the epic. `goversbx upgrade`
(D5) is slice 4; until then the verb is reserved and prints the `sbx kit add` command, as `run`
does today for an existing sandbox.

### Measured for this slice (2026-11-13, nested dev sandbox, Linux arm64)

sbx was upgraded 0.51.0 -> **0.53.0** (stable since 2026-11-11; 0.52 was skipped, 0.54.0-rc4 is
out). Rows marked 0.51+0.53 were run on both.

| # | Question | Result |
|---|---|---|
| M1 | Where does the wrapper learn the sandbox name before it exists? | `sbx env plan` prints `Name:   <name>` in its `ENVIRONMENT` block, on **stdout** (0.51+0.53). It honours `--name`, the file's `name:` and the derived `<agent>-<directory>`. There is no `--format json`/`--json`. |
| M2 | Does a project's `.sbxenv.yaml` (dotfile) count? | **No** (0.51): with only `.sbxenv.yaml` in the directory the plan lists the user defaults alone. Only `sbxenv.yaml` is a project file. The epic's D4 wording ("neither `sbxenv.yaml` nor `.sbxenv.yaml`") is corrected by this slice. |
| M3 | User defaults without a project file | `sbx env plan` works from `~/.sbxenv.yaml` alone if it has an `agent` (0.51+0.53). **Without a `workspace` anywhere the sandbox gets no workspace mount at all** - no error, no warning. With `workspace: ${{ env.projectDir }}` in the user defaults the current directory is mounted. |
| M4 | No `agent` in either file | `error: invalid <files>: agent is required` (0.53). |
| M5 | Neither file exists | `error: no sbxenv.yaml found at <cwd>/sbxenv.yaml` (0.53). |
| M6 | A path argument | `sbx env plan .` skips the user defaults (0.53: fails with `agent is required` when only the defaults name the agent) - fact 9 of the epic, still true. |
| M7 | Project file lists the goversbx kit too | Same `source` as the user defaults: the plan shows it once. A second copy under another path: the plan shows **both** and does not complain (0.53); whether create refuses it is not measured. |
| M8 | `sbx env create` without a terminal | Refused with `... changes things outside the sandbox that have not been approved; approve them by running this in a terminal, or approve this invocation with --force (-f)`, exit 1 (0.51+0.53). The workspace mount alone is such a change. |
| M9 | goversbx verbs that are also sbx verbs (0.53 `sbx --help`) | `run`, **`stop`** (stops sandboxes), **`setup`** (experimental: prepares the host), `version`, `help`. The epic's D6 ("no goversbx verb shadows an sbx verb except `run`") is wrong; decided: keep the verbs, add `goversbx sbx ...` (Decisions 1). |
| M10 | `sbx env create` when the sandbox exists; `sbx env run` right after `create` (asks again?); `sbx env run --detach` | Measured 2026-11-13 evening on 0.53.0, once the pull got through. **Create on an existing sandbox fails**: `error: sandbox "<name>" already exists; attach to it with sbx env run, or remove it with sbx env rm`, exit 1 - so the launcher's "create only when `sbx ls` has no such name" is required, not an optimisation. **An approval given at the prompt is remembered**: after `create` answered with `y` on a terminal, `sbx env plan` says `already approved - applying asks nothing` and `sbx env run` proceeds without a prompt, also without a terminal - a first `goversbx run` shows exactly one prompt. **`--force` approves one invocation only**: after `create --force` the plan still says `needs approval` and `sbx env run` without `--force` and without a terminal is refused - which is why `goversbx run --force` passes `--force` to both calls (S15). `sbx env run --detach` starts the sandbox without attaching. |

What 0.53.0 changes for this slice (release notes): `sbx run` may reattach to "the current
directory's sandbox when another directory with the same name has taken the default sandbox name",
so the name must be read (M1), never derived; unused source-kit build images are collected after
seven days and rebuilt on use, which is why the kit's dockerfile needs no base image; the built-in
`gemini` agent is retired; `sbx run <agent> -- <args>` now keeps the image's entrypoint arguments,
which `goversbx run -- ARGS` inherits unchanged; confirmations and notices go to stderr.

## Contract

### CLI surface (D6)

```
goversbx run [--agent A] [--name N] [--force] [-- AGENT ARGS...]
goversbx init [--agent A]
goversbx setup [--agent A] [--check] [--quiet]
goversbx start | stop | status | logs | dashboard | version | help
goversbx upgrade            reserved for slice 4; prints the `sbx kit add` command and exits 1
goversbx sbx ARGS...        exec sbx ARGS... (the explicit way to an sbx verb goversbx shadows)
goversbx <anything else>    exec sbx <anything else> "$@", unchanged, no daemon start
```

- `goversbx help` (and no argument) prints goversbx's own verbs, the shadowed sbx verbs with their
  `goversbx sbx ...` form, then `sbx --help`. An unknown verb is not an error of goversbx - sbx
  answers it.
- Pass-through does not start the daemon, does not run `setup`, and keeps stdin/stdout/exit status
  (`exec`).
- `GOVERSBX_KITS_DIR`, `GOVERSBX_DAEMON_BIN`, `SBX`, `GOVERSBX_STATE_DIR` and the port variables
  keep their meaning.

### `goversbx setup` (D2)

Owns exactly one `kits` entry and, on first use, the `agent` of `~/.sbxenv.yaml`.

| State of `~/.sbxenv.yaml` | Result |
|---|---|
| missing | created: `schemaVersion: "1"`, `agent: <A>`, `workspace: ${{ env.projectDir }}`, `kits: [ {source: <kit dir>} ]` |
| no goversbx entry | entry appended to `kits` (list created if absent) |
| entry with another path (moved install, checkout vs. Homebrew) | `source` corrected |
| several goversbx entries | the first is kept and corrected, the others removed |
| `args.daemonPort` differs from the sandbox port in effect | set; removed when the port is 8787 |
| no `agent` | `--agent A` writes it; else on a terminal the question below; else exit 3 |
| not YAML, or `kits` is not a list | **never written**; exit 1 with the parser's message |
| everything as it should be | no write, no output |

- A goversbx entry is a `kits` item - a plain string or a mapping with `source` - whose path ends
  in `/kits/goversbx`. Other keys of that mapping and every other line of the file are left alone:
  comments, key order, quoting and blank lines survive an edit.
- The kit directory written is the launcher's `KITS_DIR/goversbx`: under Homebrew the `opt` path
  (`$(brew --prefix)/opt/goversbx/share/goversbx/kits/goversbx`), which survives `brew upgrade`.
- The port is `GOVERSBX_SANDBOX_API_PORT` of the invoking shell (default 8787).
- Before a write the old file is copied to `~/.sbxenv.yaml.goversbx-bak`; the write is temp file +
  rename in the same directory, onto the link's target if the file is a symlink. A created file is
  `0600`, an edited one keeps its mode.
- Each change is one line on stderr, `goversbx: ~/.sbxenv.yaml: <what changed>`. `--quiet`
  (what `run` uses) drops the warning lines; changes are always reported.
- **The one question.** Only when the file has no `agent`, no `--agent` was given and stdin is a
  terminal: `Default agent for new sandboxes [claude]:` with a line of examples (`claude`, `codex`,
  `opencode`, `copilot`, `kiro`, `shell`, or a kit agent such as `omp`). Empty input means `claude`.
  The answer must match `^[A-Za-z0-9][A-Za-z0-9._/:@-]*$`; it is not checked against sbx (sbx
  reports an unknown agent when the sandbox is created). Without a terminal: exit 3 and
  `goversbx: no default agent yet - run: goversbx setup --agent <name>`. An existing `agent` is
  never changed, not even by `--agent`; that is one line saying so.
- `--check` writes nothing. Exit 0 when the file is as `setup` would leave it, exit 2 with one line
  per finding otherwise (a missing `agent` is a finding). `goversbx status` prints the result as a
  third line: `user defaults: ok` or `user defaults: <n> finding(s) - run goversbx setup`.
- Also checked, reported as a warning line, never changed and never a failure: the user defaults
  have no `workspace` (M3: a project without its own file would get a sandbox without a mount).
- `setup` needs sbx >= 0.51: `sbx version` is parsed once; an older or unreadable version is one
  line and exit 1 (`goversbx: sbx 0.51 or newer is needed, found <v>`).

Where it lives: the YAML work is a subcommand of the daemon binary, the bash launcher only asks
the question and prints. `goversbx-daemon` without arguments starts the daemon as before.

```
goversbx-daemon config user-defaults --kit-dir DIR --port P [--agent A] [--check] [--file PATH]
    stdout: one line per change or finding;  exit 0 ok / 1 unusable file / 2 drift (--check) / 3 agent needed
goversbx-daemon config user-defaults --print-agent [--file PATH]
    stdout: the agent, or nothing
goversbx-daemon config project-kits --file PATH
    stdout: the `kits` sources of a project file, one per line (for the warning in `run`)
```

`--file` defaults to `~/.sbxenv.yaml` and exists for the tests. In a repo checkout the launcher
runs the same subcommand from the jar (built on demand, as for `start`).

### `goversbx init` (D4)

Writes `./sbxenv.yaml` if the current directory has none:

```yaml
schemaVersion: "1"
workspace: .
# kits:               # project mixins; goversbx itself comes from ~/.sbxenv.yaml
#   - docker.io/sbx/playwright:1.61.1
```

- `agent: A` is added as the second line only when `--agent A` was given and differs from the user
  defaults' agent.
- An existing `sbxenv.yaml` is never touched: one line (`goversbx: sbxenv.yaml exists - left as it
  is`), exit 0. `--agent` together with an existing file: exit 1, `set agent: in sbxenv.yaml`.
- `.sbxenv.yaml` in the directory is not a project file for sbx (M2): `init` writes `sbxenv.yaml`
  next to it and says so in one line.
- `init` neither starts the daemon nor runs `setup`.
- The file goes to the directory `goversbx` was called in - no search upwards, no git-root lookup.
  sbx mounts it read-only into the sandbox.

### `goversbx run` (D3)

```
1. start the daemon if it is not running                      (unchanged start_daemon)
2. setup --quiet                                              exit 1/3 from setup ends the run
3. no ./sbxenv.yaml                -> init [--agent A]        one line: "wrote sbxenv.yaml"
4. name = `sbx env plan [--name N]`, the `Name:` line         plan fails -> its output, its status
5. no sandbox of that name in `sbx ls --format json`:
       sbx env create [--name N] [--force]                    sbx shows the plan and asks
       POST /api/sandboxes/<name>/register                    failure = one warning, never fatal
6. exec sbx env run [--name N] [--force] [-- AGENT ARGS...]   never with a path argument
```

- **Positional arguments are refused**: `goversbx run claude ~/p` exits 1 with
  `goversbx: 'run' takes no agent or path any more - cd into the project and run 'goversbx run'
  (plain sbx: 'goversbx sbx run ...')`. Nothing is started, not even the daemon. The same for any
  flag other than `--agent`, `--name`, `--force`/`-f`: it names `goversbx env run ...` (pass-through)
  as the way to the full flag set.
- `--agent A` only reaches the scaffold. With an existing `sbxenv.yaml` it is refused as in `init`,
  before anything starts.
- Step 4 failing is the normal way to learn that the environment is unusable (M4: no agent, a
  broken file); goversbx adds nothing to sbx's message.
- Step 5 is skipped when the sandbox exists: nothing is asked of the daemon about it (slice 1, S8).
  A sandbox that exists without the goversbx kit is sbx's business - `sbx env run` reports what it
  would change.
- Without a terminal and without `--force`, `sbx env create` refuses (M8); its message and exit
  status are passed on, nothing is registered, `sbx env run` is not called.
- Before step 4, one warning line if the project file's `kits` name a goversbx kit
  (`config project-kits`, same `/kits/goversbx` rule): `sbxenv.yaml lists the goversbx kit too -
  remove it there, it comes from ~/.sbxenv.yaml`. The run continues.
- If the name cannot be read from the plan (format change in a later sbx), step 5 becomes plain
  `sbx env create` when `sbx env plan` shows a sandbox to add, no pre-registration, one warning;
  the daemon's registration on the agent's first call remains the fallback.

### Removed

- `goversbx run <agent> <path> [sbx run flags]` and everything that served it in the launcher:
  `target_sandbox`, `name_flag`, the `Created sandbox` parsing, the before/after `sbx ls` diff, the
  `--kit`/`--kit-arg` assembly, the `--goversbx-kit` refusal.
- The "sandbox exists - run `sbx kit add`" hint moves to `goversbx upgrade`.

### Not in this slice

- `goversbx upgrade` (slice 4). Codex registration (still unmeasured, slice 2).
- Any `lifecycle` entry, `mcp.servers` entry or sbx setting written by `setup` (D8, D9).
- Removing goversbx from `~/.sbxenv.yaml` (`setup --remove`): the user deletes one entry; Homebrew
  has no uninstall hook to do it for them. The README says so.
- Shell completion.

## Scenarios

| # | Scenario | Level |
|---|---|---|
| S1 | Given no `~/.sbxenv.yaml`, when `config user-defaults --agent claude` runs, then the file has `schemaVersion`, `agent: claude`, `workspace: ${{ env.projectDir }}` and one kit entry with the given directory; exit 0 | `unit`, `contract` |
| S2 | Given a file with comments, other kits before and after, quoted scalars and blank lines, when the goversbx entry is appended or its path corrected, then every other byte of the file is unchanged and the old content is in `.goversbx-bak` | `unit` |
| S3 | Given two goversbx entries (one a plain string, one a mapping with extra keys), when setup runs, then one remains, with the right path and its extra keys | `unit` |
| S4 | Given port 9001, then the entry carries `args.daemonPort: "9001"`; given 8787 and an entry that has `args.daemonPort`, then it is removed and other `args` stay | `unit` |
| S5 | Given a file that is not YAML, or whose `kits` is a mapping, then exit 1, a message naming the file, the file byte-identical and no backup written | `unit`, `contract` |
| S6 | Given a correct file, when setup runs twice, then the second run writes nothing (mtime unchanged) and prints nothing | `unit`, `contract` |
| S7 | Given drift, when `--check` runs, then exit 2, one line per finding, file unchanged; given none, exit 0 | `unit`, `contract` |
| S8 | Given a file without `agent` and no `--agent`: exit 3. Given one with `agent: codex` and `--agent claude`: agent stays `codex`, exit 0, one line | `unit`, `contract` |
| S9 | Given a file that is a symlink, when setup edits it, then the link stays a link and its target has the new content | `unit` |
| S10 | Given the **native** binary, `config user-defaults` creates, edits and checks a file (the YAML library needs its native-image metadata) | `contract` (`binaryTestNative`) |
| S11 | Given no agent and no terminal, when `goversbx setup` runs, then exit 3 with the `--agent` command and no file is created | `launcher` |
| S12 | Given an empty directory and user defaults with an agent, when `goversbx run` runs, then `sbxenv.yaml` is written with exactly `schemaVersion` + `workspace: .` + the commented hint, and the sbx calls are, in order, `env plan`, `env create`, the daemon's `register` for the planned name, `env run` - none with a path argument | `launcher` |
| S13 | Given an existing `sbxenv.yaml`, when `goversbx run` runs, then the file is byte-identical afterwards | `launcher` |
| S14 | Given an existing sandbox of the planned name, when `goversbx run` runs, then no `env create`, no call to the daemon about it, `env run` once | `launcher` |
| S15 | `goversbx run --name n7 --force -- --resume` calls `env plan --name n7`, `env create --name n7 --force`, registers `n7`, and `env run --name n7 --force -- --resume` | `launcher` |
| S16 | `goversbx run claude <dir>` and `goversbx run --detach`: exit 1, the one-line message, no sbx call, no daemon started | `launcher` |
| S17 | Given `env create` fails, then its status and output are passed on, no register, no `env run` | `launcher` |
| S18 | Given registration fails (wrong admin token), then one warning and `env run` still runs | `launcher` |
| S19 | Given a stale kit path in the user defaults, when `goversbx run` runs, then the path is corrected before `env plan` and one line says so | `launcher` |
| S20 | `goversbx init` in an empty directory writes the scaffold; with `--agent codex` and user default `claude` it adds `agent: codex`, with `--agent claude` it does not; with an existing file it changes nothing | `launcher` |
| S21 | `goversbx ls --format json`, `goversbx policy log`, `goversbx env rm --force` and `goversbx sbx stop x` reach sbx with exactly those arguments, with sbx's exit status and without a daemon being started | `launcher` |
| S22 | `goversbx status` shows `user defaults: ok` after setup and the finding count after the kit entry was removed by hand | `launcher` |
| S23 | Given real sbx >= 0.51 and the user defaults written by `goversbx setup --agent claude`, when the real launcher runs `goversbx run --force` in an empty directory, then the sandbox named by `sbx env plan` exists, has the skill and `goversbx-network` registered, the workspace is mounted, and its first `request_network_access` is pending under its own name without "please retry" | `e2e` (`LauncherSequenceTest`, rewritten) |
| S24 | Given real sbx, a project `sbxenv.yaml` with its own `env` entry and the user defaults, when `goversbx run --force` runs, then the sandbox has both the project's variable and the goversbx kit | `e2e` |
| S25 | The installed sbx prints `Name:` in `sbx env plan` and has `env plan/create/run` with `--name` and `--force` where this slice uses them | `e2e` (`SbxCliContractTest`) |

`unit`: `daemon/src/test` (the editor, no process). `contract`: `daemon/src/binaryTest`, the
`config` subcommand as a process against a temp `--file`. `launcher`:
`scripts/testing/test-launcher.sh` with `HOME` pointed at a temp directory; `fake-sbx` gains
`version` and `env plan|create|run|rm` (name from `--name`, the file's `name:`, else
`<agent>-<directory>`; `FAKE_SBX_CREATE_FAIL` applies to `env create`). Launcher checks 1-6 of
slices 1 and 2 are rewritten onto the new form; their scenarios (create -> register -> attach, no
host-set call, daemon trouble never blocks) stay pinned by S12, S14, S17, S18.

## Also changed

- `README.md` quick start (`brew install`, `goversbx run` in the project), the verb table and the
  note on removing the entry; the Homebrew caveats; `CHANGELOG.md` 0.6.0 with the breaking change;
  `AGENTS.md`/`docs/status.md`; `scripts/e2e-*.sh` create their sandboxes through `sbx create --kit`
  directly (they test the kit, not the launcher) and need no change beyond what slice 2 did.
- `docs/threat-model.adoc`: goversbx now writes a host file sbx acts on (`~/.sbxenv.yaml`) - what
  it may change there (one entry, one scalar), and that a **repo checkout** points the user
  defaults at `sandbox-kit/kits/goversbx` inside a directory that may itself be mounted writable
  into a sandbox (this dev sandbox is one): an agent working on goversbx can change the kit every
  other sandbox gets next. The installed layout does not have that property.
- Epic: D4's file-name wording (M2), D6's shadowing claim (M9), the 0.53 notes.

## Decisions (user, 2026-11-13)

All five proposals were accepted as written:

1. **Shadowed verbs (M9).** goversbx keeps `run`, `stop`, `setup`, `version`, `help`;
   `goversbx sbx ...` is the explicit way to the sbx verb of the same name. No `goversbx daemon`.
2. **The agent question** is free text with examples and default `claude`, not a fixed list (sbx
   has no command that lists its built-in agents; 0.53 retired `gemini`).
3. **The scaffold is always written** when the directory has no `sbxenv.yaml`, also when the user
   defaults already mount the project (M3): the project file is what makes a plain `sbx env run`
   without goversbx defaults mount the workspace, and it is the place for project kits.
4. **`run` knows `--agent`, `--name`, `--force`** and nothing else; the full flag set is
   `goversbx env run ...`, which loses only the pre-registration.
5. **Minimum sbx: >= 0.51**; 0.6.0 names 0.53 as the tested version once `:daemon:e2eTest` is
   green there.

## Implementation notes (2026-11-13, where the code is more specific than the text above)

- **Exit 3 writes nothing**, also for an existing file that only lacks the agent: the launcher asks
  and calls again with `--agent`. So a `run` without a default agent and without a terminal changes
  no file at all.
- **The unreadable-plan fallback is simpler than specified**: when no `Name:` line is found the
  launcher does not call `sbx env create` itself; it prints one warning and goes straight to
  `sbx env run`, which creates the sandbox when needed. Same outcome, one sbx call fewer.
- **`goversbx stop <args>` is refused** with the `goversbx sbx stop <args>` form, instead of
  stopping the daemon and ignoring the arguments - the one place where the shadowed verb could do
  something the user did not mean.
- **The home directory is `$HOME`**, falling back to the JVM's `user.home`: sbx resolves
  `~/.sbxenv.yaml` through `$HOME`, and both must mean the same file.
- **What the editor refuses** (exit 1, file untouched, a line saying what to do by hand): invalid
  YAML; a top level that is not a block mapping when a key must be added; `kits` that is neither a
  list nor empty; a goversbx entry written as a flow mapping (`{source: ...}`) or with flow-style
  `args` when `daemonPort` has to be added or removed; several goversbx entries in a flow list
  (`[...]`). Path corrections and appends work in flow lists too.
- A `--check` on a missing file reports one finding (`the file does not exist`), exit 2.
- The e2e tests (S23, S24) write the **real** `~/.sbxenv.yaml` of the machine they run on - sbx
  reads no other - and restore it (and goversbx's backup of it) afterwards.
- **2026-11-14, first e2e run on the host (sbx 0.53.0):** S25 passed. S23 and S24 failed in the
  tests' own assumption that the machine's default agent is `claude` (the host has `agent: omp`
  and the omp-sbx kits in `~/.sbxenv.yaml`); the launcher's part worked on real sbx in both. The
  tests now set the real file aside and let `setup` create a fresh one - they no longer just
  "write and restore" it. Not rerun yet.
- Scenario S9 (symlink) and S2-S4 are pinned in `UserDefaultsEditorTest` / `ConfigCommandTest`
  (`daemon/src/test/.../userdefaults`); S1, S5-S8 also in `binaryTest`'s `ConfigCommandTest`.

## Open questions

- None for the design. M10 is measured (table above) and changed nothing in the flow.
  `:daemon:e2eTest` on 0.53.0 (S23-S25) and `binaryTestNative` (S10) are still to be run; a first
  host run on 2026-11-13 failed in the suite's setup on an image pull timeout, before any test.
