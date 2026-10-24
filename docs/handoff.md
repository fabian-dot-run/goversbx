# Handoff (kurz) — goversbx-omp auf sbx kit v3

2026-10-24 · Details, Beweismatrix und Verifikationsblock: [`handoff-goversbx-omp-v3.md`](handoff-goversbx-omp-v3.md)

## Stand omp-sbx (`~/IdeaProjects/omp-sbx`, uncommitted, Tests grün)

Extra-kits support: `--kit` / `--kit-arg` (wird vom Launcher konsumiert, nicht an omp
durchgereicht), `OMP_SBX_EXTRA_KITS` + `OMP_SBX_EXTRA_KIT_ARGS` aus `.env` oder exportierter
Umgebung (exported wins, exported-leer entfernt das Projekt-Kit), `~`-Expansion,
Pfad-Validierung vor dem Create, Dupes collapse, kit-args last-one-wins pro Name,
Mixin-Drift-Warnung (`not attached` / `still attached` → `--new`), `--dry-run`.

Geändert: `omp-sbx`, `sbx-preflight.sh` (`normalize_kit_ref`, `recorded_kits`,
`kit_mixin_drift`, `list_contains`), `omp-sbxenv`, `test-kit.sh` (neue Sektion
*launcher kit wiring* + `TEST_SKIP_SANDBOX=1`), `README.md`.
`kits/omlx-mixin/` ist wieder auf HEAD — sein Dockerfile-Staging funktioniert.

`TEST_SKIP_SANDBOX=1 ./test-kit.sh` → 24 passed, 0 failed.

## Zwei bewiesene Blocker (E2e auf echtem sbx v0.49.1)

- **A — v2-Kit fräst v3-Mixin-Inhalte weg.** `omp + omlx` → `/opt/omp-sbx/extensions/omlx-provider.ts`
  vorhanden; `omp + omlx + goversbx-omp (v2)` → weg, in beiden Reihenfolgen. Zwei v3-Mixins mit
  Inhalt komponieren sauber. Runtime-String: *"mixin composition is accepted in the schema but not
  yet applied by the runtime"*. Folge heute: oMLX-Provider verschwindet still, wenn goversbx
  dabei ist.
- **B — `files/home/.omp/**` kollidiert mit dem Host-Mount.** omp-sbx mountet `~/.omp`; ein v2-Kit,
  das `files/home/.omp/agent/skills/…` shipped, lässt `sbx create` mit `error: failed to run
  sandbox container` sterben (Minimal-Repro mit genau einer Datei im Detail-Doc).
  `com.docker.sandbox/agent-skills@1` ist kein Ausweg — es mountet den Host-Skills-Store.

## Entscheidung

Option 1: `sandbox-kit/kits/goversbx-omp` auf `schemaVersion: "3"` portieren —
`kit.yaml` + `kit.dockerfile`, Payload nach `/opt/goversbx/` (außerhalb von `$HOME` und
außerhalb aller Pfade, die der omp-Workload anlegt), Skill-Installation zur Laufzeit durch
`register-omp-mcp.sh`. Andere Kits bleiben v2.

## Nächster Schritt

1. Spec unter `docs/specs/` (AGENTS.md-Konvention) mit vier Szenarien: Komposition mit
   v3-Content-Kit, `~/.omp` als Host-Mount, Skill + `mcp.json` im laufenden Sandbox,
   `sbx kit add` weiterhin verweigert.
2. Port: `kit.yaml`, `kit.dockerfile`, `.dockerignore`, `stage/opt/goversbx/…`; `spec.yaml` und
   `files/` erst nach Gradle- und Skript-Umstellung löschen.
3. `sandbox-kit/build.gradle.kts`: `kitSkillDirs["goversbx-omp"]` → `stage/opt/goversbx/skills`.
4. `scripts/e2e-omp.sh` Schritt 0 und 5 anpassen + neuer Schritt, dass **beide** Dateien
   (`/opt/goversbx/register-omp-mcp.sh` und `/opt/omp-sbx/extensions/omlx-provider.ts`) in einem
   Sandbox existieren.
5. AGENTS.md §"Sandbox kit", README §"The sbx kits", "Verification state", CHANGELOG,
   Version-Bump-Checkliste (sie nennt "vier `kits/*/spec.yaml`").

## Umgebung

Daemon läuft (sandbox 8787, admin 8788, 0.3.0-dev). oMLX auf `127.0.0.1:8000`, kein API-Key
(`OMLX_API_KEY=proxy-managed`). Homebrew-Kit ist 0.2.7 → beim Iterieren den Repo-Pfad (0.3.0)
benutzen. sbx v0.49.1 installiert, v0.49.2 verfügbar → Blocker-A-Matrix nach einem Upgrade erneut
fahren. Alle Probe-Sandboxes entfernt, `~/.omp/agent/mcp.json` restauriert.

## Nutzung, sobald das Kit v3 ist

```bash
# .env im Projekt
OMP_SBX_OMLX=1
OMP_SBX_EXTRA_KITS=~/IdeaProjects/goversbx/sandbox-kit/kits/goversbx-omp
omp-sbx --dry-run && omp-sbx --new
```
