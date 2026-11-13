package run.fabian.daemon.userdefaults;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 0003 slice 3, scenarios S1 and S5-S9 at the file level. */
class ConfigCommandTest {

    private static final String KIT = "/opt/goversbx/share/goversbx/kits/goversbx";

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int userDefaults(Path file, String... more) {
        List<String> args = new ArrayList<>(List.of("user-defaults", "--kit-dir", KIT, "--file", file.toString()));
        args.addAll(List.of(more));
        return run(args);
    }

    private int run(List<String> args) {
        out.reset();
        err.reset();
        return new ConfigCommand(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
    }

    private Path backupOf(Path file) {
        return Path.of(file + ".goversbx-bak");
    }

    @Test
    void aMissingFileIsCreatedOwnerOnly() throws IOException {
        Path file = dir.resolve(".sbxenv.yaml");

        assertEquals(ConfigCommand.OK, userDefaults(file, "--agent", "claude"));

        assertEquals("schemaVersion: \"1\"\nagent: claude\nworkspace: ${{ env.projectDir }}\nkits:\n  - source: " + KIT + "\n",
                Files.readString(file));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertFalse(Files.exists(backupOf(file)), "nothing to back up");
        assertTrue(out.toString().contains("created"));
    }

    @Test
    void withoutAnAgentNothingIsCreatedAndTheExitSaysSo() {
        Path file = dir.resolve(".sbxenv.yaml");

        assertEquals(ConfigCommand.AGENT_NEEDED, userDefaults(file));
        assertFalse(Files.exists(file));
    }

    @Test
    void anExistingFileWithoutAnAgentIsLeftAloneUntilOneIsGiven() throws IOException {
        Path file = Files.writeString(dir.resolve(".sbxenv.yaml"), "workspace: .\n");

        assertEquals(ConfigCommand.AGENT_NEEDED, userDefaults(file));
        assertEquals("workspace: .\n", Files.readString(file));

        assertEquals(ConfigCommand.OK, userDefaults(file, "--agent", "codex"));
        assertEquals("workspace: .\nagent: codex\nkits:\n  - source: " + KIT + "\n", Files.readString(file));
    }

    @Test
    void anEditKeepsTheModeAndLeavesABackup() throws IOException {
        Path file = Files.writeString(dir.resolve(".sbxenv.yaml"), "agent: claude\nworkspace: .\nkits:\n  - other\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertEquals(ConfigCommand.OK, userDefaults(file));

        assertEquals("agent: claude\nworkspace: .\nkits:\n  - other\n  - source: " + KIT + "\n", Files.readString(file));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("agent: claude\nworkspace: .\nkits:\n  - other\n", Files.readString(backupOf(file)));
        assertEquals("goversbx kit added\n", out.toString());
    }

    @Test
    void aSecondRunWritesAndPrintsNothing() throws IOException {
        Path file = dir.resolve(".sbxenv.yaml");
        userDefaults(file, "--agent", "claude");
        FileTime before = FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(file, before);

        assertEquals(ConfigCommand.OK, userDefaults(file));

        assertEquals(before, Files.getLastModifiedTime(file));
        assertEquals("", out.toString());
        assertFalse(Files.exists(backupOf(file)));
    }

    @Test
    void anUnusableFileIsNeverWritten() throws IOException {
        for (String broken : List.of("agent: [claude\n", "agent: claude\nkits:\n  a: b\n")) {
            Path file = Files.writeString(dir.resolve(".sbxenv.yaml"), broken);
            byte[] bytes = Files.readAllBytes(file);

            assertEquals(ConfigCommand.UNUSABLE, userDefaults(file, "--agent", "claude"));

            assertArrayEquals(bytes, Files.readAllBytes(file));
            assertFalse(Files.exists(backupOf(file)));
            assertTrue(err.toString().contains(file.toString()), err.toString());
            assertEquals(ConfigCommand.UNUSABLE, userDefaults(file, "--check"));
        }
    }

    @Test
    void checkReportsDriftWithoutWriting() throws IOException {
        Path file = Files.writeString(dir.resolve(".sbxenv.yaml"), "agent: claude\nworkspace: .\nkits:\n  - /old/kits/goversbx\n  - /older/kits/goversbx\n");
        byte[] bytes = Files.readAllBytes(file);

        assertEquals(ConfigCommand.DRIFT, userDefaults(file, "--check", "--port", "9001"));

        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(3, out.toString().lines().count(), out.toString());

        userDefaults(file);
        assertEquals(ConfigCommand.OK, userDefaults(file, "--check"));
        assertEquals("", out.toString());
    }

    @Test
    void checkCountsAMissingFileAndAMissingAgentAsFindings() throws IOException {
        Path missing = dir.resolve("none.yaml");
        assertEquals(ConfigCommand.DRIFT, userDefaults(missing, "--check"));
        assertEquals(ConfigCommand.DRIFT, userDefaults(missing, "--check", "--agent", "claude"));
        assertFalse(Files.exists(missing));

        Path noAgent = Files.writeString(dir.resolve("a.yaml"), "workspace: .\nkits:\n  - " + KIT + "\n");
        assertEquals(ConfigCommand.DRIFT, userDefaults(noAgent, "--check"));
        assertEquals("no default agent\n", out.toString());
    }

    @Test
    void aSymlinkStaysALinkAndItsTargetIsEdited() throws IOException {
        Path target = Files.writeString(Files.createDirectories(dir.resolve("dotfiles")).resolve("sbxenv.yaml"), "agent: claude\nworkspace: .\n");
        Path link = Files.createSymbolicLink(dir.resolve(".sbxenv.yaml"), target);

        assertEquals(ConfigCommand.OK, userDefaults(link));

        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.readString(target).contains(KIT));
        assertEquals("agent: claude\nworkspace: .\n", Files.readString(backupOf(link)));
    }

    @Test
    void theAgentAndAProjectsKitsCanBeRead() throws IOException {
        Path file = Files.writeString(dir.resolve("sbxenv.yaml"), "agent: omp\nkits:\n  - a\n  - source: /b/kits/goversbx\n");

        assertEquals(ConfigCommand.OK, run(List.of("user-defaults", "--print-agent", "--file", file.toString())));
        assertEquals("omp\n", out.toString());
        assertEquals(ConfigCommand.OK, run(List.of("project-kits", "--file", file.toString())));
        assertEquals("a\n/b/kits/goversbx\n", out.toString());
        assertEquals(ConfigCommand.OK, run(List.of("user-defaults", "--print-agent", "--file", dir.resolve("none").toString())));
        assertEquals("", out.toString());
    }

    @Test
    void badArgumentsAreAUsageError() {
        assertEquals(ConfigCommand.UNUSABLE, run(List.of()));
        assertEquals(ConfigCommand.UNUSABLE, run(List.of("user-defaults")));
        assertEquals(ConfigCommand.UNUSABLE, run(List.of("user-defaults", "--kit-dir", KIT, "--agent", "bad name")));
        assertEquals(ConfigCommand.UNUSABLE, run(List.of("user-defaults", "--kit-dir", KIT, "--port", "x")));
        assertEquals(ConfigCommand.UNUSABLE, run(List.of("nope")));
        assertTrue(err.toString().contains("usage:"));
    }
}
