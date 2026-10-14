package run.fabian.daemon.binary;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP endpoint as raw Streamable HTTP: protocol versions, both tools, every error path. */
class McpProtocolTest {

    private static DaemonProcess daemon;

    @BeforeAll
    static void start() {
        daemon = DaemonProcess.start(FakeSbx.create().running("proto-registered"));
    }

    @AfterAll
    static void stop() {
        daemon.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"})
    void initializesForEveryProtocolVersion(String version) {
        Mcp mcp = daemon.mcp("proto-" + version).initialize("""
                {"protocolVersion":"%s","capabilities":{},"clientInfo":{"name":"t","version":"0"}}""".formatted(version));
        assertEquals("goversbx-network", mcp.initializeResult.path("serverInfo").path("name").asText());
        assertTrue(mcp.initializeResult.path("capabilities").has("tools"), mcp.initializeResult.toString());
        assertFalse(mcp.initializeResult.path("protocolVersion").asText().isBlank());
        assertEquals(2, mcp.request("tools/list", "{}").path("result").path("tools").size());
    }

    @Test
    void toolsListDescribesBothToolsWithSchemas() {
        JsonNode tools = daemon.mcp("proto-list").initialize().request("tools/list", "{}").path("result").path("tools");
        JsonNode request = find(tools, "request_network_access");
        assertEquals(List.of("host", "reason"), strings(request.path("inputSchema").path("required")));
        assertEquals("string", request.path("inputSchema").path("properties").path("host").path("type").asText());
        assertEquals(120, request.path("inputSchema").path("properties").path("timeoutSeconds").path("maximum").asInt());
        assertFalse(request.path("description").asText().isBlank());
        JsonNode check = find(tools, "check_network_access_status");
        assertTrue(check.path("inputSchema").path("properties").has("requestId"), check.toString());
    }

    @Test
    void pendingThenCheckStatusThroughApproval() {
        Mcp mcp = daemon.mcp("proto-pending").initialize();
        String first = Mcp.text(mcp.callTool("request_network_access",
                Mcp.args("host", "pending.example", "reason", "r", "timeoutSeconds", 1)));
        assertTrue(first.startsWith("Still pending"), first);
        Matcher id = Pattern.compile("requestId=\"([0-9a-f-]{36})\"").matcher(first);
        assertTrue(id.find(), first);

        String stillPending = Mcp.text(mcp.callTool("check_network_access_status", Mcp.args("requestId", id.group(1))));
        assertTrue(stillPending.startsWith("Still pending"), stillPending);

        daemon.decide(id.group(1), "approve");
        String approved = Mcp.text(mcp.callTool("check_network_access_status", Mcp.args("requestId", id.group(1))));
        assertTrue(approved.startsWith("Approved"), approved);
    }

    // The MCP SDK validates arguments against the tool's input schema before our handler runs (with
    // json-schema-validator, which loads its draft meta-schemas as classpath resources - another
    // thing a native image only has if its metadata says so).
    @Test
    void schemaRejectsMissingAndOutOfRangeArguments() {
        Mcp mcp = daemon.mcp("proto-args").initialize();
        JsonNode noReason = mcp.callTool("request_network_access", Mcp.args("host", "x.example"));
        assertTrue(Mcp.isToolError(noReason), noReason.toString());
        assertTrue(Mcp.text(noReason).contains("required property 'reason' not found"), Mcp.text(noReason));

        JsonNode zeroTimeout = mcp.callTool("request_network_access",
                Mcp.args("host", "x.example", "reason", "r", "timeoutSeconds", 0));
        assertTrue(Mcp.isToolError(zeroTimeout), zeroTimeout.toString());
        assertTrue(Mcp.text(zeroTimeout).contains("/timeoutSeconds: must have a minimum value of 1"), Mcp.text(zeroTimeout));

        JsonNode tooLong = mcp.callTool("request_network_access",
                Mcp.args("host", "x.example", "reason", "r", "timeoutSeconds", 121));
        assertTrue(Mcp.isToolError(tooLong), tooLong.toString());
        assertTrue(daemon.requestsFor("x.example").isEmpty(), "rejected calls must not create requests");
    }

    @Test
    void handlerValidatesWhatTheSchemaAllows() {
        Mcp mcp = daemon.mcp("proto-blank").initialize();
        JsonNode blank = mcp.callTool("request_network_access", Mcp.args("host", " ", "reason", "r", "timeoutSeconds", 1));
        assertTrue(Mcp.isToolError(blank), blank.toString());
        assertEquals("Both 'host' and 'reason' are required.", Mcp.text(blank));

        JsonNode noId = mcp.callTool("check_network_access_status", Mcp.args());
        assertTrue(Mcp.isToolError(noId));
        JsonNode badId = mcp.callTool("check_network_access_status", Mcp.args("requestId", "nope"));
        assertTrue(Mcp.text(badId).startsWith("'requestId' is not a valid id"), badId.toString());
        UUID unknown = UUID.randomUUID();
        JsonNode unknownId = mcp.callTool("check_network_access_status", Mcp.args("requestId", unknown.toString()));
        assertEquals("Unknown requestId: " + unknown, Mcp.text(unknownId));
    }

    @Test
    void unknownToolIsAnError() {
        JsonNode response = daemon.mcp("proto-unknown").initialize().callTool("no_such_tool", Mcp.args());
        assertTrue(response.has("error") || Mcp.isToolError(response), response.toString());
    }

    @Test
    void unknownMethodIsAJsonRpcError() {
        JsonNode response = daemon.mcp("proto-method").initialize().request("no/such/method", "{}");
        assertTrue(response.has("error"), response.toString());
    }

    @Test
    void placeholderHeaderTriggersRegistration() {
        String text = Mcp.text(daemon.mcp(DaemonProcess.PLACEHOLDER).initialize()
                .callTool("request_network_access", Mcp.args("host", "p.example", "reason", "r")));
        assertTrue(text.contains("registering it now"), text);
        assertTrue(daemon.sbx.calls().contains("ls --format json"), daemon.sbx.calls().toString());
        assertTrue(daemon.sbx.calls().stream().anyMatch(c -> c.startsWith("secret set-custom --sandbox proto-registered ")),
                daemon.sbx.calls().toString());
    }

    @Test
    void missingSandboxHeaderIsRecordedAsUnknownSandbox() {
        Mcp mcp = daemon.mcp(null).initialize();
        var call = mcp.requestAccessAsync("noheader.example", 30);
        assertEquals("unknown-sandbox", daemon.awaitPending("noheader.example").path("sandboxName").asText());
        daemon.decide(daemon.awaitPending("noheader.example").path("id").asText(), "deny");
        call.join();
    }

    @Test
    void requestsWithoutASessionAreRejected() {
        Mcp mcp = daemon.mcp("proto-nosession");
        var response = mcp.post(Mcp.rpc(1, "tools/list", "{}"), false);
        assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, response.statusCode() + " " + response.body());
    }

    // The server sends the GET stream's response headers only with its first event or when the stream
    // ends, so the GET can't be awaited before the DELETE that ends it (pi's recorded traffic does
    // exactly this: GET, then DELETE).
    @Test
    void deleteEndsTheSessionAndItsEventStream() throws Exception {
        Mcp mcp = daemon.mcp("proto-get").initialize();
        CompletableFuture<HttpResponse<String>> stream = DaemonProcess.HTTP.sendAsync(HttpRequest.newBuilder(mcp.endpoint)
                        .header("Accept", "text/event-stream").header("mcp-session-id", mcp.sessionId)
                        .header("X-Goversbx-Sandbox-Name", "proto-get").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        DaemonProcess.sleep(300);
        assertFalse(stream.isDone(), "GET stream ended on its own: " + (stream.isDone() ? stream.join().body() : ""));

        var delete = DaemonProcess.send(HttpRequest.newBuilder(mcp.endpoint).timeout(Duration.ofSeconds(10))
                .header("mcp-session-id", mcp.sessionId).header("X-Goversbx-Sandbox-Name", "proto-get").DELETE().build());
        assertEquals(200, delete.statusCode(), delete.body());

        HttpResponse<String> ended = stream.get(10, TimeUnit.SECONDS);
        assertEquals(200, ended.statusCode());
        assertTrue(ended.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"), ended.headers().toString());
        var afterDelete = mcp.post(Mcp.rpc(9, "tools/list", "{}"), true);
        assertTrue(afterDelete.statusCode() >= 400, "session still usable after DELETE: " + afterDelete.statusCode());
    }

    private static JsonNode find(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("no tool " + name + " in " + tools);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }
}
