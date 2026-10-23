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
        // An agent request is a one-host request: the list form carries the same host, no origin details.
        assertEquals("shape.example", r.path("host").asText());
        assertEquals(1, r.path("hosts").size());
        assertEquals("shape.example", r.path("hosts").get(0).asText());
        assertEquals("AGENT", r.path("origin").asText());
        assertTrue(r.path("source").isNull());
        assertEquals(0, r.path("profiles").size());
        assertTrue(r.path("hostResults").isNull());
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
        assertEquals(1, decided.path("hostResults").size());
        assertEquals("approve.example", decided.path("hostResults").get(0).path("host").asText());
        assertTrue(decided.path("hostResults").get(0).path("applied").asBoolean());
        assertTrue(daemon.sbx.calls().contains("policy allow network approve.example --sandbox api-approve"), daemon.sbx.calls().toString());
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

    private static boolean contains(JsonNode list, String host) {
        for (JsonNode r : list) {
            if (host.equals(r.path("host").asText())) {
                return true;
            }
        }
        return false;
    }
}
