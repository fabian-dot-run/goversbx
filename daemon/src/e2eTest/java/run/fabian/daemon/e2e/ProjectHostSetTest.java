package run.fabian.daemon.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 0001 slice 1 against a real sbx: the project file in {@code -a}'s workspace becomes one
 * host-set request when the sandbox is registered as a new incarnation, approving it runs
 * {@code sbx policy allow} for the set's host and makes it reachable from {@code -a} only. Ends with
 * the spec's open question 3: whether the comma-separated host list works with {@code --sandbox}
 * (checked by reachability, not by exit code, on {@code -b}).
 */
@Order(5)
@ExtendWith(E2eSuite.class)
class ProjectHostSetTest {

    private static final String TRIGGER_HOST = "goversbx-e2e-set-trigger.invalid";   // never contacted; creates no request

    private static JsonNode pendingSet;

    private final E2eEnvironment env = E2eEnvironment.get();
    private final String setHost = env.config.setHost;

    private void requireWorkspace() {
        Assumptions.assumeTrue(env.a.workspace != null, "sandbox " + env.a + " has no workspace (" + env.a.workspaceNote + ")");
    }

    @Test
    @Order(1)
    void theSetHostIsBlockedBeforeTheTest() {
        assertNotEquals("200", env.a.httpStatus(setHost), setHost + " is already reachable from " + env.a + " - pick a blocked host with GOVERSBX_E2E_SET_HOST");
        assertNotEquals("200", env.b.httpStatus(setHost), setHost + " is already reachable from " + env.b);
    }

    @Test
    @Order(2)
    void aProjectFileRaisesOneSetWhenTheSandboxIsRegisteredAsANewIncarnation() {
        requireWorkspace();
        Path file = env.a.workspace.resolve(".goversbx").resolve("network.yaml");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "version: 1\nhosts:\n  - " + setHost + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Without its saved registrations the daemon sees -a as a new incarnation on the next call,
        // registers it again and announces it - the registration trigger of the spec (T1).
        Daemon daemon = env.restartDaemonWithoutSavedRegistrations();
        ToolResult first = env.a.requestAccess(TRIGGER_HOST, 1);
        assertTrue(first.says("registering it now"), "expected the re-registration round trip: " + first);

        pendingSet = daemon.awaitPendingHostSet(env.a.name, Duration.ofSeconds(30));
        assertEquals(1, pendingSet.path("hosts").size(), pendingSet.toString());
        assertEquals(setHost, pendingSet.path("hosts").get(0).asText());
        assertEquals("PROJECT_FILE", pendingSet.path("origin").asText());
        assertTrue(pendingSet.path("source").asText().endsWith(".goversbx/network.yaml"), pendingSet.toString());
        assertTrue(daemon.requestsFor(TRIGGER_HOST).isEmpty(), "the trigger call must not create a request");
    }

    @Test
    @Order(3)
    void approvingTheSetMakesTheHostReachableFromThatSandboxOnly() {
        requireWorkspace();
        JsonNode decided = env.daemon().approve(pendingSet.path("id").asText());
        assertEquals("APPROVED", decided.path("status").asText());
        assertTrue(decided.path("sbxApplied").asBoolean(false), "sbx policy allow did not apply: " + decided);
        assertTrue(env.daemon().logContains("sbx policy allow network " + setHost + " --sandbox " + env.a.name + " succeeded"),
                "no successful policy allow for the set in the daemon log:\n" + env.daemon().logTail(30));
        assertTrue(env.a.becomesReachable(setHost, Duration.ofSeconds(30)),
                setHost + " still not reachable from " + env.a + " after approving the set: HTTP " + env.a.httpStatus(setHost)
                        + " - " + env.a.blockedMessage(setHost));
        assertNotEquals("200", env.b.httpStatus(setHost), setHost + " became reachable from " + env.b + " too");
    }

    @Test
    @Order(4)
    void theExplicitTriggerFindsNothingLeftToAskFor() {
        requireWorkspace();
        HttpResponse<String> response = env.daemon().postJson("/api/sandboxes/" + env.a.name + "/host-set-requests", "");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("ALL_GRANTED"), response.body());
        // The agent asking for the set's host now gets an immediate yes, no human involved.
        ToolResult result = env.a.requestAccess(setHost, 5);
        assertTrue(result.says("Approved"), "already granted host was not answered at once: " + result);
    }

    @Test
    @Order(5)
    void aCommaSeparatedHostListIsAcceptedBySbxPolicyAllowWithSandbox() {
        // Spec 0001 open question 3, by reachability: if sbx took "a,b" as one literal domain, the
        // command might still exit 0 but neither host would open up. Uses -b, which nothing else grants.
        String denyHost = env.config.denyHost;
        assertNotEquals("200", env.b.httpStatus(setHost));
        assertNotEquals("200", env.b.httpStatus(denyHost));
        Sbx.Result result = env.sbx.policyAllowNetwork(setHost + "," + denyHost, env.b.name);
        assertTrue(result.ok(), "sbx policy allow network <a>,<b> --sandbox exits " + result.exit() + ":\n" + result.output());
        assertTrue(env.b.becomesReachable(setHost, Duration.ofSeconds(30)),
                "comma list accepted but " + setHost + " not reachable from " + env.b + ": " + env.b.blockedMessage(setHost));
        assertTrue(env.b.becomesReachable(denyHost, Duration.ofSeconds(30)),
                "comma list accepted but " + denyHost + " not reachable from " + env.b + ": " + env.b.blockedMessage(denyHost));
        System.out.println("    sbx policy allow network with a comma-separated list and --sandbox works: both hosts reachable from " + env.b);
    }
}
