package run.fabian.daemon.binary;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The admin JSON API: Jackson on our records and java.time, and every error mapping. */
class AdminApiTest {

    private static DaemonProcess daemon;

    @BeforeAll
    static void start() {
        daemon = DaemonProcess.start(FakeSbx.create());
    }

    @AfterAll
    static void stop() {
        daemon.close();
    }

    private record Pending(JsonNode request, CompletableFuture<JsonNode> call) {
    }

    private static Pending pending(String sandbox, String host) {
        CompletableFuture<JsonNode> call = daemon.asSandbox(sandbox).requestAccessAsync(host, 60);
        return new Pending(daemon.awaitPending(host), call);
    }

    @Test
    void pendingRequestHasFullShape() {
        Pending p = pending("api-shape", "shape.example");
        JsonNode r = p.request();
        UUID.fromString(r.path("id").asText());
        assertEquals("api-shape", r.path("sandboxName").asText());
        assertEquals("binary test", r.path("reason").asText());
        Instant.parse(r.path("requestedAt").asText());
        assertTrue(r.path("decidedAt").isNull());
        // A request is exactly one host: no list form, no origin details (spec 0003 slice 1).
        assertEquals("shape.example", r.path("host").asText());
        for (String gone : new String[] {"hosts", "origin", "source", "profiles", "hostResults"}) {
            assertTrue(r.path(gone).isMissingNode(), gone + " in " + r);
        }
        daemon.decide(r.path("id").asText(), "deny");
        p.call().join();
    }

    @Test
    void approveRunsSbxAndAnswersTheWaitingCall() {
        Pending p = pending("api-approve", "approve.example");
        JsonNode decided = daemon.decide(p.request().path("id").asText(), "approve");
        assertEquals("APPROVED", decided.path("status").asText());
        assertEquals("binary-test", decided.path("decidedBy").asText());
        Instant.parse(decided.path("decidedAt").asText());
        assertTrue(decided.path("sbxApplied").asBoolean());
        assertEquals("Added rule (fake sbx)", decided.path("sbxOutput").asText());
        assertTrue(daemon.sbx.calls().contains("policy allow network approve.example --sandbox api-approve --no-input"), daemon.sbx.calls().toString());
        String text = Mcp.text(p.call().join());
        assertTrue(text.startsWith("Approved - network access to 'approve.example'"), text);
    }

    @Test
    void denyLeavesSbxAlone() {
        Pending p = pending("api-deny", "deny.example");
        JsonNode decided = daemon.decide(p.request().path("id").asText(), "deny");
        assertEquals("DENIED", decided.path("status").asText());
        assertTrue(daemon.sbx.calls().stream().noneMatch(c -> c.contains("deny.example")), daemon.sbx.calls().toString());
        assertTrue(Mcp.text(p.call().join()).startsWith("Denied - network access to 'deny.example'"));
    }

    @Test
    void decidingTwiceIs409() {
        Pending p = pending("api-twice", "twice.example");
        String id = p.request().path("id").asText();
        daemon.decide(id, "approve");
        p.call().join();
        var again = daemon.postJson(daemon.admin("/api/requests/" + id + "/deny"), "{\"decidedBy\":\"x\"}");
        assertEquals(409, again.statusCode(), again.body());
        assertEquals("already decided", DaemonProcess.parse(again.body()).path("error").asText());
    }

    @Test
    void unknownIdIs404AndMalformedIdIs400() {
        var unknown = daemon.postJson(daemon.admin("/api/requests/" + UUID.randomUUID() + "/approve"), "{}");
        assertEquals(404, unknown.statusCode(), unknown.body());
        var malformed = daemon.postJson(daemon.admin("/api/requests/not-a-uuid/approve"), "{}");
        assertEquals(400, malformed.statusCode(), malformed.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "PENDING", "approved", "denied"})
    void statusFilterIsCaseInsensitive(String status) {
        JsonNode list = daemon.adminJson("/api/requests?status=" + status);
        assertTrue(list.isArray());
        list.forEach(r -> assertEquals(status.toUpperCase(), r.path("status").asText()));
    }

    @Test
    void statusFilterSeparatesStates() {
        Pending p = pending("api-filter", "filter.example");
        assertTrue(contains(daemon.adminJson("/api/requests?status=pending"), "filter.example"));
        daemon.decide(p.request().path("id").asText(), "approve");
        p.call().join();
        assertFalse(contains(daemon.adminJson("/api/requests?status=pending"), "filter.example"));
        assertTrue(contains(daemon.adminJson("/api/requests?status=approved"), "filter.example"));
        assertTrue(contains(daemon.adminJson("/api/requests"), "filter.example"));
    }

    @Test
    void unknownStatusFilterIsRejected() {
        int status = daemon.get(daemon.admin("/api/requests?status=bogus")).statusCode();
        assertTrue(status >= 400, "status " + status);
    }

    @Test
    void registerEndpointRegistersOneSandbox() {
        var response = daemon.postJson(daemon.admin("/api/sandboxes/api-register/register"), "");
        assertEquals(200, response.statusCode(), response.body());
        assertNotNull(DaemonProcess.parse(response.body()));
        assertTrue(daemon.sbx.calls().stream().anyMatch(c -> c.startsWith("secret set-custom --sandbox api-register ")),
                daemon.sbx.calls().toString());
    }

    @Test
    void aHostApprovedEarlierIsAskedForAgain() {
        Pending first = pending("api-again", "again.example");
        daemon.decide(first.request().path("id").asText(), "approve");
        first.call().join();

        // No memory of earlier grants: the same sandbox asking again gets an ordinary pending request.
        Pending second = pending("api-again", "again.example");
        assertEquals("PENDING", second.request().path("status").asText());
        assertTrue(second.request().path("decidedBy").isNull());
        JsonNode decided = daemon.decide(second.request().path("id").asText(), "approve");
        assertEquals("binary-test", decided.path("decidedBy").asText());
        second.call().join();
        assertEquals(2, daemon.sbx.calls().stream()
                .filter(c -> c.equals("policy allow network again.example --sandbox api-again --no-input")).count(), daemon.sbx.calls().toString());
    }

    @Test
    void theHostSetEndpointIsGone() {
        var response = daemon.postJson(daemon.admin("/api/sandboxes/api-register/host-set-requests"), "");
        assertEquals(404, response.statusCode(), response.body());
        var text = daemon.postJson(daemon.admin("/api/sandboxes/api-register/host-set-requests?format=text"), "");
        assertEquals(404, text.statusCode(), text.body());
    }

    private static boolean contains(JsonNode list, String host) {
        for (JsonNode r : list) {
            if (host.equals(r.path("host").asText())) {
                return true;
            }
        }
        return false;
    }
}
