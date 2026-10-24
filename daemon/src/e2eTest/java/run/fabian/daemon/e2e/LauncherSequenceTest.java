package run.fabian.daemon.e2e;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
