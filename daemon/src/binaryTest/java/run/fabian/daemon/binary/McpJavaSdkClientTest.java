package run.fabian.daemon.binary;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The official MCP Java SDK client, with every client capability it can declare switched on. */
class McpJavaSdkClientTest {

    @Test
    void fullSessionWithEveryClientCapability() {
        try (DaemonProcess daemon = DaemonProcess.start(FakeSbx.create())) {
            // The sandbox's real minted token, injected the way the sbx proxy would.
            String token = daemon.tokenFor("sdk-client");
            try (McpSyncClient client = McpClient
                     .sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + daemon.sandboxPort + "/mcp")
                             .httpRequestCustomizer((builder, method, uri, body, ctx) -> builder.header("X-Goversbx-Sandbox-Name", token))
                             .build())
                     .requestTimeout(Duration.ofSeconds(60))
                     .clientInfo(new McpSchema.Implementation("sdk-client", "SDK client", "1.0"))
                     .roots(new McpSchema.Root("file:///workspace", "workspace"))
                     .sampling(request -> new McpSchema.CreateMessageResult(McpSchema.Role.ASSISTANT,
                             new McpSchema.TextContent("unused"), "model", McpSchema.CreateMessageResult.StopReason.END_TURN))
                     .elicitation(request -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null))
                     // Form elicitation only: declaring URL elicitation also needs a URL handler in the
                     // SDK client. ClientCapabilityMatrixTest covers the url capability on the wire.
                     .capabilities(McpSchema.ClientCapabilities.builder()
                             .roots(true).sampling().elicitation(true, false).experimental(Map.of("x", Map.of())).build())
                     .build()) {

            McpSchema.InitializeResult init = client.initialize();
            assertEquals("goversbx-network", init.serverInfo().name());
            assertEquals(2, client.listTools().tools().size());

            var pending = client.callTool(McpSchema.CallToolRequest.builder("request_network_access")
                    .arguments(Map.of("host", "sdk.example", "reason", "sdk", "timeoutSeconds", 1)).build());
            String text = ((McpSchema.TextContent) pending.content().get(0)).text();
            assertTrue(text.startsWith("Still pending"), text);

            String id = daemon.awaitPending("sdk.example").path("id").asText();
            daemon.decide(id, "approve");
            var status = client.callTool(McpSchema.CallToolRequest.builder("check_network_access_status")
                    .arguments(Map.of("requestId", id)).build());
            assertTrue(((McpSchema.TextContent) status.content().get(0)).text().startsWith("Approved"));
            client.ping();
            }
        }
    }
}
