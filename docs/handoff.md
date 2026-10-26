# Handoff (kurz) — goversbx-omp auf sbx kit v3

2026-10-26 · Details, Beweismatrix und Verifikationsblock: [`handoff-goversbx-omp-v3.md`](handoff-goversbx-omp-v3.md)

## Was sich am 2026-10-26 geändert hat

**Der omp-sbx-Launcher ist gelöscht** (`~/IdeaProjects/omp-sbx` commit `ddc2bf1`):
`omp-sbx`, `omp-sbxenv`, `omp-sbx-mcp-import`, `sbx-preflight.sh` — ~1330 von 2400 Zeilen.
Sie existierten, um das gemountete Host-`~/.omp` zu verteidigen. Dieser Mount ist weg;
jedes Sandbox hat sein eigenes `~/.omp` aus dem Image.

Das entwertet **Blocker B** — gemessen, nicht gefolgert: ein v2-Kit mit
`files/home/.omp/agent/skills/probe/SKILL.md` legt die Datei sauber ab, `/proc/mounts` zeigt
keinen `~/.omp`-Eintrag. Vorher: `error: failed to run sandbox container`.

**Blocker A besteht weiter und wurde auf v0.49.2 nachgemessen** (dieses Dokument mass vorher
auf v0.49.1; der Host ist inzwischen 0.49.2): mit v2-Kit in der Komposition fehlt
`/opt/omp-sbx/extensions/omlx-provider.ts`, während die v2-Dateien da sind.

## Was das für die Spec bedeutet

`docs/specs/0002-goversbx-omp-kit-v3/epic.md` hat einen Amendment-Eintrag vom 2026-10-26
bekommen; die Originalanalyse steht darunter als Records.

- Option C (`payload unter ~/.omp/agent/skills` aus dem Dockerfile) ist durch B **nicht mehr
  ausgeschlossen**.
- v3 bleibt nötig, **falls** goversbx ein Kit shipped und mit `omlx-mixin` komponiert (A).
- Neu dazwischen: sbx deckt beide Payloads nativ ab. Skills über den Shared Store
  (`sbx skills add`/`import`), und omp-sbx deklariert `agent-skills@1` auf
  `~/.omp/agent/skills` — die kommen also read-only an, ohne Kit-`files/`. MCP über
  `sbx mcp add --url … --header …` + `--static-mcp`; `omp-init.sh` verweist schon auf die
  `MCP_GATEWAY_URL` des Sandboxes.
- **Wenn beides trägt, braucht goversbx-omp gar kein Kit** und diese Spec ist gegenstandslos.

## Nächster Schritt

1. **Verifizieren, ob der MCP-Gateway den `X-Goversbx-Sandbox-Name`-Platzhalter durchreicht.**
   Das ist der einzige offene Punkt, der über "Kit löschen oder portieren" entscheidet.
   Registriert ist `goversbx-network` auf diesem Host bereits (`sbx mcp add`, URL
   `http://127.0.0.1:8787/mcp`).
2. Danach entscheiden: native Integration (Store + static MCP, Spec 0002 schließen) oder
   v3-Port wie spekuliert.
3. Falls Port: `goversbx-omp.yaml` + `goversbx-omp.dockerfile`, Payload nach `/opt/goversbx/`,
   `spec.yaml`/`files/` erst nach Gradle- und Skript-Umstellung löschen;
   `sandbox-kit/build.gradle.kts` `kitSkillDirs` anpassen; `scripts/e2e-omp.sh` erweitern, dass
   **beide** Dateien in einem Sandbox existieren.

Abnahme bleibt beim User (Repo-Regel).

## Beweislage, unverändert gültig

- **A — v2-Kit fräst v3-Mixin-Inhalte weg.** `omp + omlx` → `omlx-provider.ts` vorhanden;
  `omp + omlx + goversbx-omp (v2)` → weg, in beiden Reihenfolgen. Zwei v3-Mixins mit Inhalt
  komponieren sauber. Runtime-String: *"mixin composition is accepted in the schema but not yet
  applied by the runtime"*.
- omp-sbx hat jetzt einen Build-Zeit-Guard dagegen (`test-kit.sh`: kein Kit im Repo darf v2 sein;
  Guard gegen synthetischen Verstoß geprüft, also nicht vakk).

## Umgebung

Daemon läuft (sandbox 8787, admin 8788, 0.3.0-dev). oMLX auf `127.0.0.1:8000`, kein API-Key
(`OMLX_API_KEY=proxy-managed`). sbx **v0.49.2**. Der sandboxd war festgefahren
(`delete runtime: context canceled`, Sandboxes mit 19–20 h Alter kurz nach Anlage — VM-Uhr
verschoben); gefixt per `sbx daemon restart`, alle Probe-Sandboxes entfernt. Host
`~/.sbxenv.yaml` wurde nur zum Proben angelegt und ist wieder gelöscht.

## Nutzung heute (Launcher ist weg)

```bash
cd ~/code/my-project
# oMLX-Mixin (v3) — sauber, beide Kits sind v3
sbx create ~/IdeaProjects/omp-sbx/kits/omp "$PWD" \
  --kit ~/IdeaProjects/omp-sbx/kits/omlx-mixin --kit-arg omlx-mixin.port=8000 \
  --static-mcp goversbx-network
sbx shell omp-my-project
```

Der Name kommt nativ aus Kit + Workspace-Verzeichnis (`omp-<basename>`), `--name` ist optional.
`OMP_SBX_*`-Flags aus `.env` existieren nicht mehr — Defaults laufen über `~/.sbxenv.yaml`,
Projektkonfiguration über `./sbxenv.yaml` (`sbx env plan|apply|run`).

**Nicht** zusätzlich `goversbx-omp` (v2) anhängen, solange oMLX dabei ist — das ist Blocker A:
dann fehlt `omlx-provider.ts` im Sandbox. Genau darauf wartet der Guard in omp-sbx's
`test-kit.sh`.
