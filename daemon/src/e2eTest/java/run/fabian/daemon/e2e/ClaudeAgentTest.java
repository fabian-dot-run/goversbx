package run.fabian.daemon.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real Claude Code (`claude -p`) in the kit sandbox, asked to curl a blocked host WITHOUT any mention
 * of goversbx: it must reach for the tool on its own (on a real host it once followed the sandbox's
 * "ask the user to run sbx policy approval" text instead). Needs Claude credentials inside the
 * sandbox, so it only runs with GOVERSBX_E2E_AGENTS=claude.
 */
@Order(7)
@Tag("agent")
@ExtendWith(E2eSuite.class)
class ClaudeAgentTest {

    private final E2eEnvironment env = E2eEnvironment.get();

    @Test
    void claudeUsesGoversbxOnItsOwnAndTheHostBecomesReachable() throws Exception {
        assumeTrue(env.config.agentEnabled("claude"), "set GOVERSBX_E2E_AGENTS=claude to drive real Claude Code");
        String host = env.config.denyHost;              // the denial earlier left no policy behind
        assertNotEquals("200", env.a.httpStatus(host), host + " is already reachable - pick a blocked host with GOVERSBX_E2E_DENY_HOST");

        String prompt = "curl https://" + host + " and tell me the final HTTP status code.";
        Path output = env.config.runDir.resolve("claude.txt");
        String cd = env.a.workspace == null ? "" : "cd '" + env.a.workspace + "' 2>/dev/null; ";
        CompletableFuture<Sbx.Result> claude = CompletableFuture.supplyAsync(() -> env.a.bash(Map.of("PROMPT", prompt),
                env.config.agentTimeout.plusSeconds(30),
                cd + "claude -p \"$PROMPT\" --allowedTools \"Bash\" \"mcp__goversbx-network\" \"Skill\" < /dev/null 2>&1"));

        boolean approved = false;
        long deadline = System.nanoTime() + env.config.agentTimeout.toNanos();
        while (!claude.isDone() && System.nanoTime() < deadline) {
            Optional<JsonNode> pending = env.daemon().pendingFor(host);
            if (!approved && pending.isPresent()) {
                assertEquals(env.a.name, pending.get().path("sandboxName").asText(), "Claude's request carries the wrong sandbox name");
                JsonNode decided = env.daemon().approve(pending.get().path("id").asText());
                assertTrue(decided.path("sbxApplied").asBoolean(false), "approval did not apply: " + decided);
                approved = true;
            }
            Daemon.sleep(2000);
        }
        Sbx.Result result;
        try {
            result = claude.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            claude.cancel(true);
            throw new AssertionError("claude -p did not finish within " + env.config.agentTimeout, e);
        }
        try {
            Files.writeString(output, result.output());
        } catch (IOException ignored) {
            // the output is also in the report
        }
        assertTrue(approved, "Claude never created a goversbx request for " + host + " - it did not use goversbx on its own:\n" + result.output());
        System.out.println("    Claude's answer: " + result.lastLine());
        assertTrue(env.a.becomesReachable(host, Duration.ofSeconds(30)), host + " still not reachable after Claude's approved request");
    }
}
