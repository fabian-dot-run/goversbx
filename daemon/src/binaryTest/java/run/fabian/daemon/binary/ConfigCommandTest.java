package run.fabian.daemon.binary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code goversbx-daemon config ...} as a process (spec 0003 slice 3: S1, S5-S8, S10): the file work
 * behind {@code goversbx setup/init/run}. It runs in the same binary as the daemon, so its YAML
 * parsing and every branch here are part of the native image's metadata - a path without a test
 * fails only in the released binary.
 */
class ConfigCommandTest {

    private static final String KIT = "/opt/goversbx/share/goversbx/kits/goversbx";

    @TempDir
    Path dir;

    private record Result(int exit, String out, String err) {
    }

    private Result config(String... args) {
        List<String> command = new ArrayList<>(List.of(System.getProperty("goversbx.daemon.command").split("\n")));
        command.add("config");
        command.addAll(List.of(args));
        try {
            Path out = Files.createTempFile(dir, "out", ".txt");
            Path err = Files.createTempFile(dir, "err", ".txt");
            ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
            builder.environment().put("HOME", dir.resolve("home").toString());
            // A proxied JVM announces its options on stderr; not part of the contract.
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            Process process = builder.start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "config did not finish: " + command);
            return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private Result userDefaults(Path file, String... more) {
        List<String> args = new ArrayList<>(List.of("user-defaults", "--kit-dir", KIT, "--file", file.toString()));
        args.addAll(List.of(more));
        return config(args.toArray(String[]::new));
    }

    @Test
    void createsTheUserDefaultsAndThenNeedsNothing() throws IOException {
        Path file = dir.resolve(".sbxenv.yaml");

        Result created = userDefaults(file, "--agent", "claude");

        assertEquals(0, created.exit(), created.err());
        assertEquals("schemaVersion: \"1\"\nagent: claude\nworkspace: ${{ env.projectDir }}\nkits:\n  - source: " + KIT + "\n",
                Files.readString(file));
        assertEquals("created with agent claude and the goversbx kit\n", created.out());

        Result again = userDefaults(file);
        assertEquals(0, again.exit(), again.err());
        assertEquals("", again.out());
        assertEquals(0, userDefaults(file, "--check").exit());
    }

    @Test
    void withoutHomeOverrideTheFileIsTheOneInHome() throws IOException {
        Result created = config("user-defaults", "--kit-dir", KIT, "--agent", "codex", "--port", "9001");

        assertEquals(0, created.exit(), created.err());
        String text = Files.readString(dir.resolve("home").resolve(".sbxenv.yaml"));
        assertTrue(text.contains("agent: codex") && text.contains("      daemonPort: \"9001\"\n"), text);
        assertEquals("codex\n", config("user-defaults", "--print-agent").out());
    }

    @Test
    void editsAnExistingFileInPlaceAndKeepsABackup() throws IOException {
        String original = "# mine\nschemaVersion: \"1\"\nagent: 'claude'  # quoted\nworkspace: .\nkits:\n  - other   # theirs\n"
                + "  - source: /old/kits/goversbx\n    args:\n      daemonPort: \"9001\"\n  - /older/kits/goversbx\n";
        Path file = Files.writeString(dir.resolve(".sbxenv.yaml"), original);

        Result edited = userDefaults(file);

        assertEquals(0, edited.exit(), edited.err());
        assertEquals("# mine\nschemaVersion: \"1\"\nagent: 'claude'  # quoted\nworkspace: .\nkits:\n  - other   # theirs\n  - source: " + KIT + "\n",
                Files.readString(file));
        assertEquals(original, Files.readString(Path.of(file + ".goversbx-bak")));
        assertEquals(3, edited.out().lines().count(), edited.out());
    }

    @Test
    void checkReportsDriftAndWritesNothing() throws IOException {
        Path file = Files.writeString(dir.resolve(".sbxenv.yaml"), "agent: claude\nkits:\n  - /old/kits/goversbx\n");
        byte[] bytes = Files.readAllBytes(file);

        Result check = userDefaults(file, "--check");

        assertEquals(2, check.exit(), check.err());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertTrue(check.out().contains("goversbx kit path corrected to " + KIT), check.out());
        assertTrue(check.out().contains("warning: no 'workspace'"), check.out());
    }

    @Test
    void anUnusableFileIsRefusedAndLeftAlone() throws IOException {
        for (String broken : List.of("agent: [claude\n", "agent: claude\nkits:\n  a: b\n", "- just\n- a list\n")) {
            Path file = Files.writeString(dir.resolve(".sbxenv.yaml"), broken);

            Result result = userDefaults(file, "--agent", "claude");

            assertEquals(1, result.exit(), result.out());
            assertTrue(result.err().contains(file.toString()), result.err());
            assertEquals(broken, Files.readString(file));
            assertFalse(Files.exists(Path.of(file + ".goversbx-bak")));
        }
    }

    @Test
    void aMissingAgentIsItsOwnExitAndAnExistingOneIsKept() throws IOException {
        Path file = dir.resolve(".sbxenv.yaml");

        assertEquals(3, userDefaults(file).exit());
        assertFalse(Files.exists(file));

        Files.writeString(file, "agent: codex\nworkspace: .\n");
        Result kept = userDefaults(file, "--agent", "claude");
        assertEquals(0, kept.exit(), kept.err());
        assertTrue(Files.readString(file).startsWith("agent: codex\n"));
        assertTrue(kept.out().contains("note: the default agent stays codex"), kept.out());
    }

    @Test
    void readsAProjectsKitsAndRejectsBadUsage() throws IOException {
        Path project = Files.writeString(dir.resolve("sbxenv.yaml"),
                "schemaVersion: \"1\"\nworkspace: .\nkits:\n  - docker.io/sbx/playwright:1.61.1\n  - source: /x/kits/goversbx\n");

        Result kits = config("project-kits", "--file", project.toString());
        assertEquals(0, kits.exit(), kits.err());
        assertEquals("docker.io/sbx/playwright:1.61.1\n/x/kits/goversbx\n", kits.out());
        assertEquals("", config("project-kits", "--file", dir.resolve("none.yaml").toString()).out());

        Result usage = config("user-defaults", "--agent", "not a name", "--kit-dir", KIT);
        assertEquals(1, usage.exit());
        assertTrue(usage.err().contains("usage:"), usage.err());
        assertEquals(1, config("nope").exit());
    }
}
