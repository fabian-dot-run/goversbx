package run.fabian.daemon.e2e;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `goversbx upgrade` against the real sbx (spec 0003 slice 4, S11-S13): sandboxes created WITHOUT
 * the goversbx kit get it afterwards - through `sbx kit add`, and through the exec fallback for
 * sandboxes sbx cannot add a kit to.
 */
@Order(6)
@ExtendWith(E2eSuite.class)
class UpgradeTest {

    private static final String PROBE_HOST = "goversbx-e2e-upgrade-check.invalid";   // never contacted; denied
    private static final String SKILL = "/home/agent/.claude/skills/goversbx-network-access/SKILL.md";

    private final E2eEnvironment env = E2eEnvironment.get();

    @Test
    void upgradeAddsTheKitToAnExistingSandboxAndLeavesItAloneAfterwards() throws Exception {
        String name = env.config.sandboxPrefix + "-u";
        Path ws = Files.createDirectories(env.config.runDir.resolve("project-u"));
        Sandbox u = createWithoutKit(name, ws);
        try {
            assertFalse(u.fileExists(SKILL), "a sandbox created without the kit already has the goversbx skill");
            u.sh("echo survives > /home/agent/keep.txt");

            // sbx asks to approve the kit's network rules and takes that answer from a terminal only.
            Launch upgrade = launch(ws, true, "upgrade");
            assertEquals(0, upgrade.exit, upgrade.out);
            assertTrue(upgrade.out.contains(name + " has goversbx now"), upgrade.out);

            assertTrue(env.sbx.run("inspect", name, "--format", "json").stdout().contains(env.config.kit().toString()),
                    "sbx inspect does not list the goversbx kit after the upgrade");
            assertTrue(u.fileExists(SKILL), "the goversbx skill is missing after the upgrade");
            assertTrue(u.readFile("/home/agent/.claude.json").contains(env.config.daemonUrlFromSandbox() + "/mcp"),
                    "goversbx-network is not registered with the daemon's URL after the upgrade");
            assertEquals("survives", u.sh("cat /home/agent/keep.txt").lastLine(), "a file in the sandbox's home did not survive the upgrade");
            assertPendingUnderOwnName(u, name);

            Launch again = launch(ws, false, "upgrade", name);
            assertEquals(0, again.exit, again.out);
            assertTrue(again.out.contains("already has the goversbx kit"), again.out);

            // The permissions are granted now: a refresh needs no terminal.
            Launch refresh = launch(ws, false, "upgrade", name, "--refresh");
            assertEquals(0, refresh.exit, refresh.out);
            assertTrue(u.fileExists(SKILL), "the goversbx skill is missing after the refresh");
        } finally {
            u.remove();
        }
    }

    @Test
    void theExecFallbackBringsGoversbxWithoutTheKit() throws Exception {
        String name = env.config.sandboxPrefix + "-x";
        Path ws = Files.createDirectories(env.config.runDir.resolve("project-x"));
        Sandbox x = createWithoutKit(name, ws);
        try {
            Launch upgrade = launch(ws, false, "upgrade", name, "--exec");
            assertEquals(0, upgrade.exit, upgrade.out);
            assertTrue(upgrade.out.contains("gone when the sandbox is recreated"), upgrade.out);

            assertFalse(env.sbx.run("inspect", name, "--format", "json").stdout().contains(env.config.kit().toString()),
                    "the exec fallback must not show up as a kit");
            assertTrue(x.daemonHealth().contains("\"ok\""), name + " cannot reach the daemon after `upgrade --exec`: " + x.daemonHealth());
            assertTrue(x.readFile("/home/agent/.claude.json").contains(env.config.daemonUrlFromSandbox() + "/mcp"),
                    "goversbx-network is not registered after `upgrade --exec`");
            // Without a mixin kit sbx mounts the shared skills store read-only at ~/.claude/skills
            // (sbx 0.53): the skill is linked where that is possible, and said so where it is not.
            assertTrue(x.fileExists(SKILL) || upgrade.out.contains("skill not linked"),
                    "the skill is neither linked nor reported as not linkable:\n" + upgrade.out);
            assertPendingUnderOwnName(x, name);
        } finally {
            x.remove();
        }
    }

    private Sandbox createWithoutKit(String name, Path workspace) {
        Sbx.Result created = env.sbx.run(Duration.ofMinutes(15),
                List.of("create", "--no-progress", "--name", name, "claude", workspace.toString()));
        assertTrue(created.ok(), "sbx create " + name + " (without a kit) failed:\n" + created.output());
        return Sandbox.existing(env.config, env.sbx, name, "claude", env.config.kit(), workspace);
    }

    private void assertPendingUnderOwnName(Sandbox sandbox, String name) {
        ToolResult first = sandbox.requestAccess(PROBE_HOST, 1);
        assertFalse(first.says("registering it now"), "the upgrade's registration did not hold: " + first);
        assertTrue(first.says("Still pending"), first.toString());
        var pending = env.daemon().awaitPending(PROBE_HOST, Duration.ofSeconds(20));
        assertEquals(name, pending.path("sandboxName").asText());
        env.deny(pending.path("id").asText());
    }

    private record Launch(int exit, String out) {
    }

    /**
     * Runs scripts/goversbx in {@code dir} against this suite's daemon. With {@code answerYes} it
     * runs on a pseudo-terminal (util-linux or BSD `script`) and "y" is typed at sbx's prompt.
     */
    private Launch launch(Path dir, boolean answerYes, String... args) throws Exception {
        Path launcher = env.config.kitsDir.getParent().getParent().resolve("scripts").resolve("goversbx");
        List<String> goversbx = new ArrayList<>(List.of("bash", launcher.toString()));
        goversbx.addAll(List.of(args));
        List<String> command = goversbx;
        if (answerYes) {
            boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
            command = new ArrayList<>(mac ? List.of("script", "-q", "/dev/null") : List.of("script", "-qec", String.join(" ", goversbx), "/dev/null"));
            if (mac) {
                command.addAll(goversbx);
            }
        }
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        if (!answerYes) {
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        }
        pb.environment().put("SBX", env.config.sbxBinary.toString());
        pb.environment().put("GOVERSBX_KITS_DIR", env.config.kitsDir.toString());
        pb.environment().put("GOVERSBX_ADMIN_PORT", Integer.toString(env.config.adminPort));
        pb.environment().put("GOVERSBX_SANDBOX_API_PORT", Integer.toString(env.config.sandboxApiPort));
        pb.environment().put("GOVERSBX_ADMIN_TOKEN", E2eConfig.ADMIN_TOKEN);
        pb.environment().put("GOVERSBX_STATE_DIR", env.config.runDir.resolve("launcher-state").toString());
        System.out.println("$ (cd " + dir + " && scripts/goversbx " + String.join(" ", args) + ")" + (answerYes ? "   [pty, answering y]" : ""));
        Process process = pb.start();
        if (answerYes) {
            // sbx builds the kit before it asks; the answer waits in the terminal's input until then.
            try (var stdin = process.getOutputStream()) {
                Thread.sleep(3000);
                stdin.write("y\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                assertTrue(process.waitFor(15, TimeUnit.MINUTES), "goversbx " + String.join(" ", args) + " did not finish");
            }
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, TimeUnit.MINUTES), "goversbx " + String.join(" ", args) + " did not finish");
        System.out.println("    " + out.strip().replace("\n", "\n    "));
        return new Launch(process.exitValue(), out);
    }
}
