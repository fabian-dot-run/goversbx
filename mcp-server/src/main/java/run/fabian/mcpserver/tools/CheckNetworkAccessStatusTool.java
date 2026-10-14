package run.fabian.mcpserver.tools;

import run.fabian.common.NetworkAccessRequest;
import run.fabian.mcpserver.DaemonClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CheckNetworkAccessStatusTool {

    private CheckNetworkAccessStatusTool() {
    }

    public static McpServerFeatures.SyncToolSpecification create(DaemonClient daemonClient) {
        Map<String, Object> requestIdProperty = new LinkedHashMap<>();
        requestIdProperty.put("type", "string");
        requestIdProperty.put("description", "The requestId returned by a previous request_network_access call");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("requestId", requestIdProperty);

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", java.util.List.of("requestId"));

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("check_network_access_status")
                .description("Check the current status of a previously-submitted network access request "
                        + "without waiting - use after request_network_access returned 'pending'.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handle(daemonClient, request))
                .build();
    }

    private static McpSchema.CallToolResult handle(DaemonClient daemonClient, McpSchema.CallToolRequest request) {
        Object requestId = request.arguments().get("requestId");
        if (requestId == null || requestId.toString().isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("'requestId' is required.")
                    .build();
        }

        try {
            NetworkAccessRequest result = daemonClient.getStatus(requestId.toString());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(RequestNetworkAccessTool.describe(result))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Failed to reach the goversbx daemon: " + e.getMessage())
                    .build();
        }
    }
}
