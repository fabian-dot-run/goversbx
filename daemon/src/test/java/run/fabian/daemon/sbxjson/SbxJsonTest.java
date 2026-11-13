package run.fabian.daemon.sbxjson;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 0003 slice 4, S1 and S2. The JSON shapes are sbx 0.53.0's, taken from a real host. */
class SbxJsonTest {

    private static final String KIT = "/opt/homebrew/opt/goversbx/share/goversbx/kits/goversbx";

    private static final String LS = """
            [
              {"name": "claude-shop", "id": "1", "agent": "claude", "status": "stopped",
               "mounts": ["/Users/me/shop:/Users/me/shop", "/Users/me/Library/Application Support/x:/run/sbx/skills-shared:ro"],
               "workspaces": ["/Users/me/shop"], "created_at": "2026-10-14T10:55:00Z"},
              {"name": "omp-shop", "agent": "omp", "status": "running",
               "workspaces": ["/Users/me/shop", "/Users/me/shop/sbxenv.yaml:ro"]},
              {"name": "claude-me", "agent": "claude", "status": "stopped", "workspaces": ["/Users/me"]},
              {"name": "claude-sub", "agent": "claude", "status": "stopped", "workspaces": ["/Users/me/shop/scripts"]},
              {"name": "ro-shop", "agent": "codex", "status": "stopped", "workspaces": ["/tmp/x", "/Users/me/shop:ro"]},
              {"name": "claude-trio", "agent": "claude", "status": "stopped", "workspaces": ["/Users/me/Documents/Agentic Trio"]},
              {"name": "shell-1", "agent": "shell", "status": "stopped", "created_at": "2026-10-25T09:49:37Z"}
            ]
            """;

    @Test
    void onlySandboxesWithTheDirectoryItselfAreListed() {
        assertEquals(List.of("claude-shop\tclaude\tstopped", "omp-shop\tomp\trunning", "ro-shop\tcodex\tstopped"),
                SbxJson.workspaceSandboxes(LS, List.of("/Users/me/shop/")));
        assertEquals(List.of("claude-trio\tclaude\tstopped"),
                SbxJson.workspaceSandboxes(LS, List.of("/nowhere", "/Users/me/Documents/Agentic Trio")));
        assertEquals(List.of(), SbxJson.workspaceSandboxes(LS, List.of("/Users/me/other")));
        assertEquals(List.of(), SbxJson.workspaceSandboxes("[]", List.of("/x")));
    }

    @Test
    void aSandboxWithThisInstallsKit() {
        String json = """
                {"id": "2b05", "created_at": "2026-11-13T18:17:54Z", "name": "up-a", "agent": "shell",
                 "kits": ["docker.io/sbx/shell@sha256:89ed", "%s/"],
                 "state": "running", "created_version": "v0.53.0", "sessions": 2,
                 "host_mounts": [{"container_target": "/w", "host_path": "/w", "read_only": false},
                                 {"container_target": "/w/sbxenv.yaml", "host_path": "/w/sbxenv.yaml", "read_only": true}]}
                """.formatted(KIT);

        assertEquals(List.of("name=up-a", "kit=this", "kit_path=" + KIT, "created_version=v0.53.0", "state=running", "sessions=2", "env=yes"),
                SbxJson.sandbox(json, KIT));
    }

    @Test
    void anotherInstallsKitNoKitAndAPre051Sandbox() {
        String other = "{\"name\": \"a\", \"kits\": [\"docker.io/sbx/claude@sha256:1\", \"/Users/me/IdeaProjects/goversbx/sandbox-kit/kits/goversbx\"], \"state\": \"stopped\", \"created_version\": \"v0.51.0\"}";
        // omp-fabench on the host: created 2026-10-10, no created_version, a v2 workload kit by path.
        String old = "{\"name\": \"omp-fabench\", \"agent\": \"omp\", \"kits\": [\"/Users/me/IdeaProjects/omp-sbx/sbx-kit\"], \"state\": \"stopped\", \"image\": \"omp-sbx:latest\", \"sessions\": 0}";

        assertEquals(List.of("name=a", "kit=other", "kit_path=/Users/me/IdeaProjects/goversbx/sandbox-kit/kits/goversbx",
                "created_version=v0.51.0", "state=stopped", "sessions=0", "env=no"), SbxJson.sandbox(other, KIT));
        assertEquals(List.of("name=omp-fabench", "kit=none", "created_version=none", "state=stopped", "sessions=0", "env=no"),
                SbxJson.sandbox(old, KIT));
        assertEquals("kit=none", SbxJson.sandbox("{\"name\": \"x\", \"kits\": [\"/a/kits/goversbx-omp\"]}", KIT).get(1));
    }

    @Test
    void whatIsNotSbxOutputIsRefused() {
        assertThrows(SbxJson.UnreadableException.class, () -> SbxJson.workspaceSandboxes("No sandboxes found", List.of("/x")));
        assertThrows(SbxJson.UnreadableException.class, () -> SbxJson.workspaceSandboxes("{}", List.of("/x")));
        assertThrows(SbxJson.UnreadableException.class, () -> SbxJson.sandbox("[]", KIT));
        assertThrows(SbxJson.UnreadableException.class, () -> SbxJson.sandbox("", KIT));
        assertThrows(SbxJson.UnreadableException.class, () -> SbxJson.sandbox("error: sandbox not found", KIT));
    }

    @Test
    void theCommandReadsStdinAndReportsBadInput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int ok = command(LS, out, err).run(List.of("workspace-sandboxes", "--dir", "/nope", "--dir", "/Users/me"));
        assertEquals(0, ok, err.toString());
        assertEquals("claude-me\tclaude\tstopped\n", out.toString());

        assertEquals(1, command("nope", out, err).run(List.of("sandbox", "--kit-dir", KIT)));
        assertTrue(err.toString().contains("did not print JSON"), err.toString());
        assertEquals(1, command(LS, out, err).run(List.of("workspace-sandboxes")));
        assertEquals(1, command(LS, out, err).run(List.of("sandbox")));
        assertEquals(1, command(LS, out, err).run(List.of("nope")));
        assertEquals(1, command(LS, out, err).run(List.of()));
        assertTrue(err.toString().contains("usage:"));
    }

    private static SbxJsonCommand command(String stdin, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        return new SbxJsonCommand(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }
}
