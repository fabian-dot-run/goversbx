package run.fabian.daemon.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable registrations against the real proxy: the secret in sbx outlives the daemon, and so must
 * the daemon's knowledge of it - and when that knowledge IS lost, re-registration overwrites the
 * secret live and the sandbox recovers after one retry.
 */
@Order(4)
@ExtendWith(E2eSuite.class)
class DaemonRestartTest {

    private static final String RESTART_HOST = "goversbx-e2e-restart-check.invalid";
    private static final String RECOVERY_HOST = "goversbx-e2e-recovery-check.invalid";

    private final E2eEnvironment env = E2eEnvironment.get();

    @Test
    @Order(1)
    void registeredSandboxesWorkOnTheFirstCallAfterARestart() {
        Daemon daemon = env.restartDaemon();
        assertTrue(daemon.logContains("sandbox registration(s)"), "restarted daemon did not report loading registrations:\n" + daemon.logTail(20));
        ToolResult result = env.a.requestAccess(RESTART_HOST, 1);
        assertFalse(result.says("registering it now"), "a registered sandbox got 'please retry' after a daemon restart: " + result);
        assertTrue(result.says("Still pending"), result.toString());
        JsonNode pending = daemon.awaitPending(RESTART_HOST, Duration.ofSeconds(20));
        assertEquals(env.a.name, pending.path("sandboxName").asText());
        env.deny(pending.path("id").asText());
    }

    @Test
    @Order(2)
    void aLostRegistrationFileIsRecoveredByReRegisteringLive() {
        Daemon daemon = env.restartDaemonWithoutSavedRegistrations();
        ToolResult first = env.a.requestAccess(RECOVERY_HOST, 1);
        assertTrue(first.says("registering it now"), "with no saved registrations the stale token must not resolve: " + first);
        ToolResult retry = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Daemon.sleep(3000);
            retry = env.a.requestAccess(RECOVERY_HOST, 1);
            if (!retry.says("registering it now")) {
                break;
            }
        }
        assertTrue(retry.says("Still pending"), "sandbox did not recover after re-registration: " + retry
                + "\n" + daemon.logTail(30));
        JsonNode pending = daemon.awaitPending(RECOVERY_HOST, Duration.ofSeconds(20));
        assertEquals(env.a.name, pending.path("sandboxName").asText(), "re-registered identity resolves to the wrong sandbox");
        env.deny(pending.path("id").asText());
        // The fresh token was written over the old secret in sbx - that is what made the retry work.
        assertTrue(countOf(daemon.log(), "sbx secret set-custom registered sandbox=" + env.a.name + " ") >= 2,
                "expected a second set-custom for " + env.a + " in the log:\n" + daemon.logTail(30));
    }

    private static int countOf(String text, String needle) {
        int count = 0, from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
