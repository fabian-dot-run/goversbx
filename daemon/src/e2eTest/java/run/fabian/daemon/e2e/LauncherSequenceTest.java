package run.fabian.daemon.e2e;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What `goversbx run` does between creating a sandbox and attaching: the sandbox is registered
 * through the admin endpoint BEFORE it ever ran a command (pre-warming), and its first MCP call must
 * then resolve to its own name without "please retry". Then the real launcher on `sbx env` (spec
 * 0003 slice 3): user defaults, scaffold, create, register.
 */
@Order(5)
@ExtendWith(E2eSuite.class)
class LauncherSequenceTest {

    private static final String PROBE_HOST = "goversbx-e2e-launcher-check.invalid";   // never contacted; denied

    private final E2eEnvironment env = E2eEnvironment.get();

    @Test
    void aSecretSetRightAfterCreateIsSubstitutedOnTheFirstCall() {
        String name = env.config.sandboxPrefix + "-c";
        StringBuilder createOutput = new StringBuilder();
        Sandbox c = Sandbox.createWithoutStart(env.config, env.sbx, name, "claude", env.config.kit(), null, createOutput);
        try {
            String status = env.sbx.sandbox(name).map(r -> r.path("status").asText()).orElse("<missing>");
            System.out.println("    " + name + " status right after create: " + status);

            HttpResponse<String> registered = env.daemon().postJson("/api/sandboxes/" + name + "/register", "");
            assertEquals(200, registered.statusCode(), registered.body());
            assertTrue(registered.body().contains("\"applied\":true"), "set-custom on a never-started sandbox failed: " + registered.body());

            ToolResult first = c.requestAccess(PROBE_HOST, 1);
            assertFalse(first.says("registering it now"),
                    "QUESTION 4 = NO: the secret set before the sandbox first ran was not substituted - the launcher must "
                            + "register after attach instead. Answer: " + first);
            assertTrue(first.says("Still pending"), first.toString());
            assertEquals(name, env.daemon().awaitPending(PROBE_HOST, Duration.ofSeconds(20)).path("sandboxName").asText());
            System.out.println("    QUESTION 4 = YES: registered while '" + status + "', substituted on the first call");
            env.deny(env.daemon().awaitPending(PROBE_HOST, Duration.ofSeconds(5)).path("id").asText());
        } finally {
            c.remove();
        }
    }

    /**
     * The real launcher against the real sbx and this suite's daemon (spec 0003 slice 3, S23): in an
     * empty project directory `goversbx run` writes the scaffold, creates the sandbox through
     * `sbx env` with the goversbx kit from the user defaults, and registers it before the agent runs.
     */
    @Test
    void goversbxRunInAnEmptyDirectoryCreatesARegisteredSandboxWithTheKit() throws Exception {
        String name = env.config.sandboxPrefix + "-d";
        Path ws = Files.createDirectories(env.config.runDir.resolve("project-d"));
        try (UserDefaults ignored = UserDefaults.setAside(env.config.runDir)) {
            Launch setup = launch(ws, "setup", "--agent", "claude");
            assertEquals(0, setup.exit, setup.out);
            String defaults = Files.readString(UserDefaults.FILE);
            assertTrue(defaults.contains("source: " + env.config.kit()) && defaults.contains("\"" + env.config.sandboxApiPort + "\""),
                    "goversbx setup did not write the kit entry and the daemon port:\n" + defaults);

            Launch run = launch(ws, "run", "--name", name, "--force");
            Sandbox d = Sandbox.existing(env.config, env.sbx, name, "claude", env.config.kit(), ws);
            try {
                assertEquals(0, run.exit, run.out);
                assertFalse(run.out.contains("WARNING"), run.out);
                assertTrue(run.out.contains("wrote sbxenv.yaml"), run.out);
                assertEquals("schemaVersion: \"1\"\nworkspace: .", Files.readString(ws.resolve("sbxenv.yaml")).lines().limit(2).reduce((a, b) -> a + "\n" + b).orElse(""));
                assertTrue(run.out.contains("attach skipped: env run --name " + name), "the launcher did not attach to " + name + ":\n" + run.out);
                assertTrue(env.sbx.sandbox(name).isPresent(), "no sandbox " + name + " after goversbx run:\n" + run.out);

                // The kit came from ~/.sbxenv.yaml alone - the project file names no kit.
                assertTrue(d.fileExists("/home/agent/.claude/skills/goversbx-network-access/SKILL.md"), "the goversbx skill is missing in " + name);
                assertTrue(d.readFile("/home/agent/.claude.json").contains(env.config.daemonUrlFromSandbox() + "/mcp"),
                        "goversbx-network is not registered with the daemon port from the user defaults");
                // The scaffold's `workspace: .` mounted the project: the sandbox sees the file the launcher wrote.
                assertTrue(d.sh("ls '" + ws + "'").output().contains("sbxenv.yaml"), "the project directory is not mounted in " + name);

                // The launcher's registration holds: the first call is an ordinary pending request.
                ToolResult first = d.requestAccess(PROBE_HOST, 1);
                assertFalse(first.says("registering it now"), "the launcher's registration did not hold: " + first);
                assertTrue(first.says("Still pending"), first.toString());
                var pending = env.daemon().awaitPending(PROBE_HOST, Duration.ofSeconds(20));
                assertEquals(name, pending.path("sandboxName").asText());
                env.deny(pending.path("id").asText());

                // A second run attaches as-is.
                Launch again = launch(ws, "run", "--name", name, "--force");
                assertEquals(0, again.exit, again.out);
                assertTrue(again.out.contains("attach skipped: env run --name " + name), again.out);
            } finally {
                d.remove();
            }
        }
    }

    /** S24: what the project declares and what the user defaults bring end up in one sandbox. */
    @Test
    void aProjectsOwnEnvironmentFileComposesWithTheUserDefaults() throws Exception {
        String name = env.config.sandboxPrefix + "-e";
        Path ws = Files.createDirectories(env.config.runDir.resolve("project-e"));
        String projectFile = "schemaVersion: \"1\"\nname: " + name + "\nworkspace: .\nenv:\n  GOVERSBX_E2E_MARK: from-project\n";
        Files.writeString(ws.resolve("sbxenv.yaml"), projectFile);
        try (UserDefaults ignored = UserDefaults.setAside(env.config.runDir)) {
            assertEquals(0, launch(ws, "setup", "--agent", "claude").exit);

            Launch run = launch(ws, "run", "--force");
            Sandbox e = Sandbox.existing(env.config, env.sbx, name, "claude", env.config.kit(), ws);
            try {
                assertEquals(0, run.exit, run.out);
                assertEquals(projectFile, Files.readString(ws.resolve("sbxenv.yaml")), "goversbx run changed an existing sbxenv.yaml");
                assertTrue(run.out.contains("attach skipped: env run"), run.out);
                assertEquals("from-project", e.sh("echo \"$GOVERSBX_E2E_MARK\"").lastLine(), "the project's env entry is missing");
                assertTrue(e.fileExists("/home/agent/.claude/skills/goversbx-network-access/SKILL.md"), "the goversbx kit is missing in " + name);
            } finally {
                e.remove();
            }
        }
    }

    private record Launch(int exit, String out) {
    }

    /**
     * Runs scripts/goversbx in {@code dir}. `sbx env run` would attach an interactive agent, so the
     * launcher gets a wrapper as its sbx that passes everything through except that call, which it
     * only records.
     */
    private Launch launch(Path dir, String... args) throws Exception {
        Path wrapper = env.config.runDir.resolve("sbx-no-attach");
        Files.writeString(wrapper, "#!/bin/sh\n[ \"$1 $2\" = \"env run\" ] && { echo \"attach skipped: $*\"; exit 0; }\nexec '"
                + env.config.sbxBinary + "' \"$@\"\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path launcher = env.config.kitsDir.getParent().getParent().resolve("scripts").resolve("goversbx");

        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("bash", launcher.toString()));
        command.addAll(java.util.List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        pb.environment().put("SBX", wrapper.toString());
        pb.environment().put("GOVERSBX_KITS_DIR", env.config.kitsDir.toString());
        pb.environment().put("GOVERSBX_ADMIN_PORT", Integer.toString(env.config.adminPort));
        pb.environment().put("GOVERSBX_SANDBOX_API_PORT", Integer.toString(env.config.sandboxApiPort));
        pb.environment().put("GOVERSBX_ADMIN_TOKEN", E2eConfig.ADMIN_TOKEN);
        pb.environment().put("GOVERSBX_STATE_DIR", env.config.runDir.resolve("launcher-state").toString());
        System.out.println("$ (cd " + dir + " && SBX=" + wrapper + " scripts/goversbx " + String.join(" ", args) + ")");
        Process process = pb.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, TimeUnit.MINUTES), "launcher did not finish");
        System.out.println("    " + out.strip().replace("\n", "\n    "));
        return new Launch(process.exitValue(), out);
    }

    /**
     * sbx reads the user defaults from the real {@code ~/.sbxenv.yaml} - there is no way to point it
     * elsewhere - so these tests use the real file. What the machine has there (another default
     * agent, other kits - a workload kit of its own would not even compose with `claude`) must not
     * decide the outcome: the file is set aside for the test, `goversbx setup` creates a fresh one,
     * and the original (and goversbx's backup of it) is put back afterwards. A copy is kept in the
     * run directory in case the JVM dies in between.
     */
    private static final class UserDefaults implements AutoCloseable {
        static final Path FILE = Path.of(System.getenv("HOME"), ".sbxenv.yaml");
        private static final Path BACKUP = Path.of(FILE + ".goversbx-bak");
        private final byte[] file;
        private final byte[] backup;

        private UserDefaults(byte[] file, byte[] backup) {
            this.file = file;
            this.backup = backup;
        }

        static UserDefaults setAside(Path runDir) throws java.io.IOException {
            UserDefaults saved = new UserDefaults(Files.exists(FILE) ? Files.readAllBytes(FILE) : null,
                    Files.exists(BACKUP) ? Files.readAllBytes(BACKUP) : null);
            if (saved.file != null) {
                Path copy = runDir.resolve("sbxenv.yaml.set-aside");
                Files.write(copy, saved.file);
                System.out.println("    ~/.sbxenv.yaml set aside for this test (copy: " + copy + ")");
                Files.delete(FILE);
            }
            return saved;
        }

        @Override
        public void close() throws java.io.IOException {
            restore(FILE, file);
            restore(BACKUP, backup);
        }

        private static void restore(Path path, byte[] content) throws java.io.IOException {
            if (content == null) {
                Files.deleteIfExists(path);
            } else {
                Files.write(path, content);
            }
        }
    }
}
