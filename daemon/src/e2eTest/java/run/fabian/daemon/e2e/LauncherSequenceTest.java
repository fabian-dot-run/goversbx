package run.fabian.daemon.e2e;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 0001 slice 2, question 4 and decision 1: what `goversbx run` will do between `sbx create` and
 * attaching. A fresh sandbox {@code -c} is created and registered through the admin endpoint BEFORE
 * it ever ran a command; its first MCP call must then resolve to its own name without "please retry".
 * Also pins the create output line the launcher parses for the name.
 */
@Order(6)
@ExtendWith(E2eSuite.class)
class LauncherSequenceTest {

    private static final String PROBE_HOST = "goversbx-e2e-launcher-check.invalid";   // never contacted; denied

    private final E2eEnvironment env = E2eEnvironment.get();

    @Test
    void aSecretSetRightAfterCreateIsSubstitutedOnTheFirstCall() {
        String name = env.config.sandboxPrefix + "-c";
        StringBuilder createOutput = new StringBuilder();
        Sandbox c = Sandbox.createWithoutStart(env.config, env.sbx, name, "claude", env.config.kit("claude"), null, createOutput);
        try {
            // Decision 1: the launcher reads the name from this line.
            assertTrue(createOutput.toString().contains("Created sandbox " + name),
                    "sbx create no longer prints 'Created sandbox <name>':\n" + createOutput);
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
     * Scenario 9: the real launcher (scripts/goversbx run) against the real sbx and this suite's daemon.
     * The final `sbx run <name>` would attach an interactive agent, so the launcher gets a wrapper as
     * its sbx that passes every command through except that one, which it only records.
     */
    @Test
    void theLauncherPutsTheSetInFrontOfTheHumanBeforeTheAgentRuns() throws Exception {
        String name = env.config.sandboxPrefix + "-d";
        String setHost = env.config.setHost;
        Path ws = env.config.runDir.resolve("workspace-d");
        Files.createDirectories(ws.resolve(".goversbx"));
        Files.writeString(ws.resolve(".goversbx").resolve("network.yaml"), "version: 1\nhosts:\n  - " + setHost + "\n");
        Path wrapper = env.config.runDir.resolve("sbx-no-attach");
        Files.writeString(wrapper, "#!/bin/sh\n[ \"$1\" = run ] && { echo \"attach skipped: $*\"; exit 0; }\nexec '"
                + env.config.sbxBinary + "' \"$@\"\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path launcher = env.config.kitsDir.getParent().getParent().resolve("scripts").resolve("goversbx");

        ProcessBuilder pb = new ProcessBuilder("bash", launcher.toString(), "run", "--name", name, "claude", ws.toString())
                .redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        pb.environment().put("SBX", wrapper.toString());
        pb.environment().put("GOVERSBX_KITS_DIR", env.config.kitsDir.toString());
        pb.environment().put("GOVERSBX_ADMIN_PORT", Integer.toString(env.config.adminPort));
        pb.environment().put("GOVERSBX_SANDBOX_API_PORT", Integer.toString(env.config.sandboxApiPort));
        pb.environment().put("GOVERSBX_ADMIN_TOKEN", E2eConfig.ADMIN_TOKEN);
        pb.environment().put("GOVERSBX_STATE_DIR", env.config.runDir.resolve("launcher-state").toString());
        System.out.println("$ SBX=" + wrapper + " scripts/goversbx run --name " + name + " claude " + ws);
        Process process = pb.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, TimeUnit.MINUTES), "launcher did not finish");
        System.out.println(out.strip().replace("\n", "\n    "));
        Sandbox d = Sandbox.existing(env.config, env.sbx, name, "claude", env.config.kit("claude"), ws);
        try {
            assertEquals(0, process.exitValue(), out);
            assertTrue(out.contains("Created sandbox " + name), out);
            assertTrue(out.contains("project host set (1 host from " + ws.resolve(".goversbx").resolve("network.yaml")
                    + ") is waiting for approval in the dashboard"), out);
            assertTrue(out.contains("attach skipped: run " + name), "the launcher did not attach to " + name + ":\n" + out);

            // Before the sandbox made a single MCP call, the set is already waiting.
            var set = env.daemon().awaitPendingHostSet(name, Duration.ofSeconds(5));
            assertNotEquals("200", d.httpStatus(setHost), setHost + " reachable from " + name + " before the approval");
            env.daemon().approve(set.path("id").asText());
            assertTrue(d.becomesReachable(setHost, Duration.ofSeconds(30)), setHost + " not reachable from " + name + " after approving the set");

            ToolResult first = d.requestAccess(setHost, 5);
            assertFalse(first.says("registering it now"), "the launcher's registration did not hold: " + first);
            assertTrue(first.says("Approved"), "the agent's request for a granted host was not answered at once: " + first);
        } finally {
            d.remove();
        }
    }
}
