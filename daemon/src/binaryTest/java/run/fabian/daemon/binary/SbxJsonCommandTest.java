package run.fabian.daemon.binary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code goversbx-daemon sbx-json ...} as a process (spec 0003 slice 4: S1, S2): what
 * {@code goversbx upgrade} and {@code goversbx run} learn from sbx's JSON. Part of the native
 * image's metadata like every other path in this suite.
 */
class SbxJsonCommandTest {

    private static final String KIT = "/opt/goversbx/share/goversbx/kits/goversbx";

    @TempDir
    Path dir;

    private record Result(int exit, String out, String err) {
    }

    private Result sbxJson(String stdin, String... args) {
        List<String> command = new ArrayList<>(List.of(System.getProperty("goversbx.daemon.command").split("\n")));
        command.add("sbx-json");
        command.addAll(List.of(args));
        try {
            Path in = Files.writeString(Files.createTempFile(dir, "in", ".json"), stdin);
            Path out = Files.createTempFile(dir, "out", ".txt");
            Path err = Files.createTempFile(dir, "err", ".txt");
            ProcessBuilder builder = new ProcessBuilder(command).redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            Process process = builder.start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "sbx-json did not finish: " + command);
            return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void listsTheSandboxesOfADirectory() {
        String ls = """
                [
                  {"name": "claude-shop", "agent": "claude", "status": "stopped", "workspaces": ["/Users/me/My Shop"]},
                  {"name": "omp-shop", "agent": "omp", "status": "running", "workspaces": ["/Users/me/My Shop", "/Users/me/My Shop/sbxenv.yaml:ro"]},
                  {"name": "claude-me", "agent": "claude", "status": "stopped", "workspaces": ["/Users/me"]},
                  {"name": "shell-1", "agent": "shell", "status": "stopped"}
                ]
                """;

        Result result = sbxJson(ls, "workspace-sandboxes", "--dir", "/somewhere/else", "--dir", "/Users/me/My Shop");

        assertEquals(0, result.exit(), result.err());
        assertEquals("claude-shop\tclaude\tstopped\nomp-shop\tomp\trunning\n", result.out());
        assertEquals("", sbxJson("[]", "workspace-sandboxes", "--dir", "/x").out());
    }

    @Test
    void describesOneSandbox() {
        String withKit = "{\"name\": \"up-a\", \"agent\": \"shell\", \"kits\": [\"docker.io/sbx/shell@sha256:89ed\", \"" + KIT + "\"],"
                + " \"state\": \"running\", \"created_version\": \"v0.53.0\", \"sessions\": 1,"
                + " \"host_mounts\": [{\"host_path\": \"/w\", \"read_only\": false}, {\"host_path\": \"/w/sbxenv.yaml\", \"read_only\": true}]}";
        String old = "{\"name\": \"omp-fabench\", \"agent\": \"omp\", \"kits\": [\"/Users/me/omp-sbx/sbx-kit\"], \"state\": \"stopped\", \"sessions\": 0}";
        String other = "{\"name\": \"b\", \"kits\": [\"/checkout/sandbox-kit/kits/goversbx\"], \"state\": \"stopped\", \"created_version\": \"v0.51.0\"}";

        assertEquals("name=up-a\nkit=this\nkit_path=" + KIT + "\ncreated_version=v0.53.0\nstate=running\nsessions=1\nenv=yes\n",
                sbxJson(withKit, "sandbox", "--kit-dir", KIT).out());
        assertEquals("name=omp-fabench\nkit=none\ncreated_version=none\nstate=stopped\nsessions=0\nenv=no\n",
                sbxJson(old, "sandbox", "--kit-dir", KIT).out());
        assertEquals("name=b\nkit=other\nkit_path=/checkout/sandbox-kit/kits/goversbx\ncreated_version=v0.51.0\nstate=stopped\nsessions=0\nenv=no\n",
                sbxJson(other, "sandbox", "--kit-dir", KIT).out());
    }

    @Test
    void whatIsNotSbxJsonIsAnErrorNotAGuess() {
        for (String input : List.of("error: sandbox \"x\" not found\n", "", "[]", "{\"kits\": []}")) {
            Result result = sbxJson(input, "sandbox", "--kit-dir", KIT);
            assertEquals(1, result.exit(), input);
            assertEquals("", result.out(), input);
            assertTrue(!result.err().isBlank(), input);
        }
        assertEquals(1, sbxJson("No sandboxes found\n", "workspace-sandboxes", "--dir", "/x").exit());
        assertEquals(1, sbxJson("{}", "workspace-sandboxes", "--dir", "/x").exit());

        Result usage = sbxJson("[]", "workspace-sandboxes");
        assertEquals(1, usage.exit());
        assertTrue(usage.err().contains("usage:"), usage.err());
        assertEquals(1, sbxJson("[]", "nope").exit());
    }
}
