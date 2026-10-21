package run.fabian.daemon.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole chain, from inside the sandbox and through the sbx proxy: unsubstituted placeholder ->
 * reactive registration (`sbx ls` + `sbx secret set-custom`) -> the proxy substitutes the per-sandbox
 * token -> a pending request under the REAL sandbox name -> approval -> `sbx policy allow` -> the
 * host is reachable; plus identity isolation, denial, polling, and the tool's own refusals.
 * Methods build on each other, in @Order.
 */
@Order(3)
@ExtendWith(E2eSuite.class)
class ApprovalFlowTest {

    private static final String ISOLATION_HOST = "goversbx-e2e-isolation-check.invalid";   // never contacted; denied
    private static final String POLL_HOST = "goversbx-e2e-poll-check.invalid";             // never contacted; denied

    private static CompletableFuture<ToolResult> secondCall;
    private static JsonNode pendingRequest;

    private final E2eEnvironment env = E2eEnvironment.get();
    private final String target = env.config.targetHost;

    @Test
    @Order(1)
    void targetHostIsBlockedBeforeTheTest() {
        String code = env.a.httpStatus(target);
        assertNotEquals("200", code, target + " is already reachable from " + env.a
                + " - pick a blocked host with GOVERSBX_E2E_TARGET_HOST");
        System.out.println("    " + target + " blocked before the test: HTTP " + code + " - " + env.a.blockedMessage(target));
    }

    @Test
    @Order(2)
    void firstCallWithTheRawPlaceholderTriggersRegistrationOfThisRunsSandboxes() {
        ToolResult first = env.a.requestAccess(target, 5);
        if (first.says("Still pending") || first.says("Approved")) {
            System.out.println("    NOTE: first call went straight through - " + env.a + " was already registered");
            // A request now exists; later steps expect to create their own, so decide this one.
            env.deny(env.daemon().awaitPending(target, Duration.ofSeconds(10)).path("id").asText());
            return;
        }
        assertTrue(first.says("registering it now"), "unexpected first answer: " + first);
        awaitLog("sbx secret set-custom registered sandbox=" + env.a.name + " ", Duration.ofSeconds(30));
        // b is only registered along with a if `sbx ls` listed it as running at that moment. sbx 0.49.1
        // stops an idle sandbox (nothing attached, no exec in flight) within about a minute, so b is
        // usually NOT registered here and gets registered on its own first call instead (step 7).
        Daemon.sleep(1000);
        if (env.daemon().logContains("sbx secret set-custom registered sandbox=" + env.b.name + " ")) {
            System.out.println("    " + env.b + " was running too and got registered in the same round");
        } else {
            System.out.println("    NOTE: " + env.b + " was not running (idle sandboxes stop) and is not registered yet - "
                    + "its own first call will register it");
        }
        String log = env.daemon().log();
        assertFalse(log.contains("set-custom failed"), "a registration failed:\n" + grep(log, "set-custom"));
    }

    @Test
    @Order(3)
    void secondCallArrivesAsAPendingRequestUnderTheRealSandboxName() {
        Daemon.sleep(2000);                                  // let the proxy pick up the new secret
        secondCall = env.a.requestAccessAsync(target, 90);
        pendingRequest = env.daemon().awaitPending(target, Duration.ofSeconds(60));
        String name = pendingRequest.path("sandboxName").asText();
        if (name.equals(env.b.name)) {
            throw new AssertionError("the request from " + env.a + " arrived as " + env.b + " - sandbox-scoped secrets with the "
                    + "same placeholder collide; approvals would go to the wrong sandbox");
        }
        assertEquals(env.a.name, name, "pending request carries the wrong sandbox name (placeholder not substituted?)");
    }

    @Test
    @Order(4)
    void approvalRunsSbxPolicyAllowAndTheToolReportsApproved() throws Exception {
        JsonNode decided = env.daemon().approve(pendingRequest.path("id").asText());
        assertEquals("APPROVED", decided.path("status").asText());
        assertTrue(decided.path("sbxApplied").asBoolean(false), "daemon's sbx policy allow did not apply: " + decided);
        ToolResult second = await(secondCall, Duration.ofSeconds(120));
        assertTrue(second.says("Approved"), "second call did not return Approved: " + second);
        assertTrue(env.daemon().logContains("sbx policy allow network " + target + " --sandbox " + env.a.name + " succeeded"),
                "no successful policy allow in the daemon log:\n" + env.daemon().logTail(30));
    }

    @Test
    @Order(5)
    void approvedHostBecomesReachableFromTheSandbox() {
        assertTrue(env.a.becomesReachable(target, Duration.ofSeconds(30)),
                target + " still not reachable from " + env.a + " after the approval: HTTP " + env.a.httpStatus(target)
                        + " - " + env.a.blockedMessage(target));
        Sbx.Result policy = env.sbx.policyLsJson(env.a.name);
        if (policy.ok() && policy.output().contains(target)) {
            System.out.println("    sbx policy ls " + env.a + " --json lists " + target);
        } else {
            System.out.println("    NOTE: sbx policy ls " + env.a + " --json does not list " + target
                    + " (the reachability check above is the proof)");
        }
    }

    @Test
    @Order(6)
    void approvalIsScopedToTheRequestingSandbox() {
        assertNotEquals("200", env.b.httpStatus(target), target + " became reachable from " + env.b
                + " too - the policy rule was not sandbox-scoped");
    }

    @Test
    @Order(7)
    void identitiesStayPerSandboxDespiteTheSharedPlaceholder() {
        // b was running during the first registration, so it should be registered already; allow a
        // couple of "please retry" rounds in case it wasn't.
        ToolResult result = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            result = env.b.requestAccess(ISOLATION_HOST, 1);
            if (!result.says("registering it now")) {
                break;
            }
            Daemon.sleep(3000);
        }
        assertTrue(result.says("Still pending"), "request from " + env.b + " did not become pending: " + result);
        JsonNode pending = env.daemon().awaitPending(ISOLATION_HOST, Duration.ofSeconds(20));
        assertEquals(env.b.name, pending.path("sandboxName").asText(),
                "request from " + env.b + " arrived under another name - the shared placeholder does not resolve per sandbox");
        env.deny(pending.path("id").asText());
    }

    @Test
    @Order(8)
    void denialLeavesTheHostBlocked() throws Exception {
        String denyHost = env.config.denyHost;
        assertNotEquals("200", env.a.httpStatus(denyHost), denyHost + " is already reachable - pick a blocked host with GOVERSBX_E2E_DENY_HOST");
        CompletableFuture<ToolResult> call = env.a.requestAccessAsync(denyHost, 60);
        JsonNode pending = env.daemon().awaitPending(denyHost, Duration.ofSeconds(30));
        JsonNode decided = env.daemon().deny(pending.path("id").asText());
        assertEquals("DENIED", decided.path("status").asText());
        ToolResult result = await(call, Duration.ofSeconds(90));
        assertTrue(result.says("Denied"), "tool did not report the denial: " + result);
        assertFalse(env.daemon().logContains("sbx policy allow network " + denyHost), "a denial must not run sbx policy allow");
        assertNotEquals("200", env.a.httpStatus(denyHost), denyHost + " became reachable after a denial");
    }

    @Test
    @Order(9)
    void pendingRequestsCanBePolledWithCheckStatus() {
        ToolResult first = env.a.requestAccess(POLL_HOST, 1);
        assertTrue(first.says("Still pending"), first.toString());
        String requestId = first.requestId();
        ToolResult polled = env.a.checkStatus(requestId);
        assertTrue(polled.says("pending") || polled.says("Pending") || polled.says("PENDING"), "poll before the decision: " + polled);
        env.deny(requestId);
        ToolResult afterwards = env.a.checkStatus(requestId);
        assertTrue(afterwards.says("Denied") || afterwards.says("DENIED") || afterwards.says("denied"), "poll after the denial: " + afterwards);
    }

    @Test
    @Order(10)
    void toolRefusesWildcardsAndTheDaemonsOwnLoopback() {
        ToolResult wildcard = env.a.requestAccess("*.example.org", 1);
        assertTrue(wildcard.isError() || wildcard.says("not") , "wildcard host was not refused: " + wildcard);
        assertTrue(env.daemon().requestsFor("*.example.org").isEmpty(), "a request for a wildcard host was created");
        ToolResult loopback = env.a.requestAccess("localhost", 1);
        assertTrue(loopback.isError() || loopback.says("not allowed"), "bare localhost was not refused: " + loopback);
        assertTrue(env.daemon().requestsFor("localhost").isEmpty(), "a request for the daemon's own loopback was created");
    }

    private void awaitLog(String needle, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!env.daemon().logContains(needle)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("daemon log never contained '" + needle + "':\n" + grep(env.daemon().log(), "sbx"));
            }
            Daemon.sleep(500);
        }
    }

    private static <T> T await(CompletableFuture<T> future, Duration timeout) throws ExecutionException, InterruptedException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the in-sandbox MCP call did not return within " + timeout, e);
        }
    }

    private static String grep(String text, String needle) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
