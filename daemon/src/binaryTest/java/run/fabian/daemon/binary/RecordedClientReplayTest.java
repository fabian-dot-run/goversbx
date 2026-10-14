package run.fabian.daemon.binary;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the MCP traffic recorded from real clients (client-fixtures/, re-record with
 * scripts/testing/record-client-fixtures.sh) byte for byte - headers, protocol versions,
 * capabilities and all - then makes one tool call in that client's session.
 */
class RecordedClientReplayTest {

    private static DaemonProcess daemon;

    @BeforeAll
    static void start() {
        daemon = DaemonProcess.start(FakeSbx.create());
    }

    @AfterAll
    static void stop() {
        daemon.close();
    }

    static Stream<Path> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("goversbx.fixturesDir")))) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void replaysRecordedClient(Path fixture) throws Exception {
        JsonNode recording = DaemonProcess.parse(Files.readString(fixture));
        String sandbox = "replay-" + fixture.getFileName().toString().replace(".json", "");
        String sessionId = null;
        JsonNode lastInitialize = null;
        boolean deleted = false;
        List<Map.Entry<Integer, CompletableFuture<HttpResponse<String>>>> streams = new ArrayList<>();

        for (JsonNode step : recording.path("steps")) {
            HttpRequest.Builder request = HttpRequest.newBuilder(daemon.sandboxApi("/mcp")).timeout(Duration.ofSeconds(30))
                    .header("X-Goversbx-Sandbox-Name", sandbox);
            step.path("headers").fields().forEachRemaining(h -> request.header(h.getKey(), h.getValue().asText()));
            if (step.path("usesSession").asBoolean()) {
                assertNotNull(sessionId, "fixture uses a session before initialize: " + step);
                request.header("mcp-session-id", sessionId);
            }
            String method = step.path("method").asText();
            String label = recording.path("client").asText() + " " + method + " " + step.path("body").asText();
            if (method.equals("GET")) {
                // The client's server-to-client event stream. The server only answers it with its
                // first event or when the session ends (a later DELETE), so don't wait for it here.
                HttpRequest.Builder get = HttpRequest.newBuilder(daemon.sandboxApi("/mcp"))
                        .header("X-Goversbx-Sandbox-Name", sandbox).header("mcp-session-id", sessionId);
                step.path("headers").fields().forEachRemaining(h -> get.header(h.getKey(), h.getValue().asText()));
                streams.add(Map.entry(step.path("expectStatus").asInt(),
                        DaemonProcess.HTTP.sendAsync(get.GET().build(), HttpResponse.BodyHandlers.ofString())));
                continue;
            }
            if (method.equals("DELETE")) {
                deleted = true;
            }
            request.method(method, step.path("body").asText().isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(step.path("body").asText()));
            HttpResponse<String> response = DaemonProcess.send(request.build());
            assertEquals(step.path("expectStatus").asInt(), response.statusCode(), label + " -> " + response.body());

            JsonNode body = step.path("body").asText().isEmpty() ? null : DaemonProcess.parse(step.path("body").asText());
            if (response.statusCode() == 200 && body != null && body.has("id")) {
                JsonNode rpc = Mcp.rpcResponse(response, body.path("id").asLong());
                assertEquals(step.path("expectJsonRpcError").asBoolean(), rpc.has("error"), label + " -> " + rpc);
                if ("initialize".equals(body.path("method").asText())) {
                    sessionId = response.headers().firstValue("mcp-session-id").orElseThrow();
                    lastInitialize = body;
                }
            }
        }

        assertNotNull(lastInitialize, "fixture has no initialize");
        for (var stream : streams) {
            if (deleted) {
                HttpResponse<String> ended = stream.getValue().get(10, TimeUnit.SECONDS);
                assertEquals(stream.getKey(), ended.statusCode(), "GET stream after DELETE");
                assertTrue(ended.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            } else {
                stream.getValue().cancel(true);
            }
        }
        if (sessionId != null && !deleted) {
            Mcp mcp = new Mcp(daemon.sandboxApi("/mcp"), sandbox);
            mcp.sessionId = sessionId;
            String text = Mcp.text(mcp.callTool("request_network_access",
                    Mcp.args("host", sandbox + ".example", "reason", "replay", "timeoutSeconds", 1)));
            assertTrue(text.startsWith("Still pending"), text);
        }
    }

}
