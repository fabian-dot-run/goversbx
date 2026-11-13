# goversbx: Netzwerkzugriff für KI-Agenten – mit einem Menschen, der Ja sagt

KI-Coding-Agenten in [Docker Sandboxes](https://docs.docker.com/ai/sandboxes/) (`sbx`) dürfen
nur die Hosts erreichen, die ihre Netzwerk-Policy erlaubt. Braucht der Agent einen neuen Host –
eine Doku-Seite, eine Paket-Registry, eine API – läuft er gegen ein 403. Dann bleibt der Agent
stehen, und du gibst den Host von Hand mit `sbx policy …` frei.

**goversbx** macht daraus einen kurzen Dialog: Der Agent fragt selbst nach, du entscheidest mit
einem Klick. Vorab musst du nichts festlegen, auch nicht pro Projekt. Ein neues Projekt kostet
deshalb nur ein `cd` und ein `goversbx run`.

## So funktioniert's

1. Der Agent ruft das MCP-Tool `request_network_access` auf: welcher Host, und warum.
2. Die Anfrage erscheint in einer lokalen Web-Oberfläche, auf Wunsch auch als Browser-Benachrichtigung.
3. Du klickst *Approve* oder *Deny*.
4. goversbx führt `sbx policy allow network <host> --sandbox <name>` aus, und der Agent macht weiter.

Der Daemon läuft auf deinem Rechner, außerhalb jeder Sandbox. Welche Sandbox fragt, weist der
sbx-Proxy nach. Ein Agent kann sich also nicht als eine andere Sandbox ausgeben. Jede Freigabe
gilt für genau einen Host und genau eine Sandbox.

In die Sandbox kommt goversbx über ein sbx-Kit. Es ist ein einziges Kit für alle Agenten:
Claude Code, opencode, pi und oh-my-pi.

## Installation (macOS, Apple Silicon)

```bash
brew tap fabian-dot-run/goversbx
brew install goversbx
```

goversbx braucht `sbx` ab Version 0.51.

## Benutzung

```bash
cd ~/mein-projekt
goversbx run
```

Das ist das ganze Setup. `goversbx run` erledigt vier Dinge:

1. Es startet den Daemon, falls er nicht läuft.
2. Es trägt das goversbx-Kit in `~/.sbxenv.yaml` ein, die Benutzer-Defaults von sbx. Beim ersten
   Mal fragt es, welchen Agenten du standardmäßig nutzt. Danach fragt es nie wieder.
3. Es legt im Projekt eine `sbxenv.yaml` an, falls es noch keine gibt.
4. Es erstellt die Sandbox mit `sbx env`, meldet sie beim Daemon an und startet den Agenten.

Weil das Kit in den Benutzer-Defaults steht, bekommt auch ein einfaches `sbx env run` goversbx
mit. Den Rest deiner `~/.sbxenv.yaml` lässt goversbx unverändert, Kommentare eingeschlossen.

Freigaben erteilst du unter <http://127.0.0.1:8788>, oder du öffnest die Oberfläche mit
`goversbx dashboard`. Einen anderen Agenten für ein neues Projekt wählst du mit
`goversbx run --agent codex`.

### Bestehende Sandboxes

Sandboxes, die es schon gibt, bekommen das Kit nicht von allein. Dafür gibt es
`goversbx upgrade`:

```bash
cd ~/altes-projekt
goversbx upgrade
```

sbx fragt dabei nach, ob die Sandbox den Daemon erreichen darf, und baut den Container neu. Was
im Home-Verzeichnis der Sandbox liegt, bleibt erhalten. Bei Sandboxes, die mit einem sbx vor 0.51
erstellt wurden, lehnt sbx das ab. Für diese richtet `goversbx upgrade <name> --exec` dasselbe
von Hand ein. Das hält, bis die Sandbox neu erstellt wird.

### Alles andere ist sbx

goversbx bringt nur wenige eigene Befehle mit. Jeden anderen reicht es unverändert an `sbx`
weiter:

```bash
goversbx ls
goversbx policy log
goversbx sbx stop mein-projekt   # für sbx-Befehle, die goversbx selbst auch kennt
```

Code und Details: [github.com/fabian-dot-run/goversbx](https://github.com/fabian-dot-run/goversbx)
