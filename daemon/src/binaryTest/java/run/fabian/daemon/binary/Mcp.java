package run.fabian.daemon.binary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static run.fabian.daemon.binary.DaemonProcess.HTTP;
import static run.fabian.daemon.binary.DaemonProcess.JSON;
import static run.fabian.daemon.binary.DaemonProcess.parse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A hand-rolled MCP Streamable HTTP client: sends exactly the JSON a test gives it, so tests control
 * what goes over the wire (protocol versions, capabilities, headers) instead of an SDK's defaults.
 */
final class Mcp {

    /** What Claude Code 2.1.300 sends (see client-fixtures/claude.json). */
    static final String CLAUDE_LIKE_INIT = """
            {"protocolVersion":"2025-11-25",
             "capabilities":{"roots":{"listChanged":true},"elicitation":{"form":{},"url":{}}},
             "clientInfo":{"name":"binary-test","title":"Binary test","version":"0"}}""";

    private static final AtomicLong IDS = new AtomicLong();

    final URI endpoint;
    final String sandboxHeader;
    String sessionId;
    JsonNode initializeResult;

    Mcp(URI endpoint, String sandboxHeader) {
        this.endpoint = endpoint;
        this.sandboxHeader = sandboxHeader;
    }

    Mcp initialize() {
        return initialize(CLAUDE_LIKE_INIT);
    }

    Mcp initialize(String paramsJson) {
        long id = IDS.incrementAndGet();
        HttpResponse<String> response = post(rpc(id, "initialize", paramsJson), false);
        if (response.statusCode() != 200) {
            fail("initialize -> " + response.statusCode() + ": " + response.body());
        }
        JsonNode rpc = rpcResponse(response, id);
        if (rpc.has("error")) {
            fail("initialize error: " + rpc);
        }
        initializeResult = rpc.path("result");
        sessionId = response.headers().firstValue("mcp-session-id")
                .orElseGet(() -> fail("initialize returned no mcp-session-id"));
        HttpResponse<String> initialized = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", true);
        if (initialized.statusCode() / 100 != 2) {
            fail("notifications/initialized -> " + initialized.statusCode() + ": " + initialized.body());
        }
        return this;
    }

    JsonNode request(String method, String paramsJson) {
        long id = IDS.incrementAndGet();
        HttpResponse<String> response = post(rpc(id, method, paramsJson), true);
        if (response.statusCode() != 200) {
            fail(method + " -> " + response.statusCode() + ": " + response.body());
        }
        return rpcResponse(response, id);
    }

    CompletableFuture<JsonNode> requestAsync(String method, String paramsJson) {
        long id = IDS.incrementAndGet();
        return HTTP.sendAsync(postRequest(rpc(id, method, paramsJson), true), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> rpcResponse(response, id));
    }

    JsonNode callTool(String name, Map<String, Object> arguments) {
        return request("tools/call", toolParams(name, arguments));
    }

    CompletableFuture<JsonNode> callToolAsync(String name, Map<String, Object> arguments) {
        return requestAsync("tools/call", toolParams(name, arguments));
    }

    /** request_network_access with a blocking timeout. */
    CompletableFuture<JsonNode> requestAccessAsync(String host, int timeoutSeconds) {
        return callToolAsync("request_network_access", args("host", host, "reason", "binary test", "timeoutSeconds", timeoutSeconds));
    }

    HttpResponse<String> post(String body, boolean withSession) {
        return DaemonProcess.send(postRequest(body, withSession));
    }

    HttpRequest postRequest(String body, boolean withSession) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(150))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sandboxHeader != null) {
            builder.header("X-Goversbx-Sandbox-Name", sandboxHeader);
        }
        if (withSession && sessionId != null) {
            builder.header("mcp-session-id", sessionId);
        }
        return builder.build();
    }

    static String rpc(long id, String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\""
                + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
    }

    static String toolParams(String name, Map<String, Object> arguments) {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        params.set("arguments", JSON.valueToTree(arguments));
        return params.toString();
    }

    /** The JSON-RPC response with this id, whether the server answered as JSON or as an SSE stream. */
    static JsonNode rpcResponse(HttpResponse<String> response, long id) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.startsWith("text/event-stream")) {
            for (String line : response.body().split("\n")) {
                if (line.startsWith("data:")) {
                    JsonNode message = parse(line.substring(5).trim());
                    if (message.path("id").asLong(-1) == id) {
                        return message;
                    }
                }
            }
            return fail("no JSON-RPC response with id " + id + " in SSE body: " + response.body());
        }
        return parse(response.body());
    }

    static String text(JsonNode response) {
        JsonNode content = response.path("result").path("content");
        if (!content.isArray() || content.isEmpty()) {
            return fail("no content in " + response);
        }
        return content.get(0).path("text").asText();
    }

    static boolean isToolError(JsonNode response) {
        return response.path("result").path("isError").asBoolean(false);
    }

    static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
