package run.fabian.daemon.mcp.tool;

import run.fabian.daemon.networkaccess.api.NetworkAccessApi;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CheckNetworkAccessStatusTool {

    private CheckNetworkAccessStatusTool() {
    }

    public static McpServerFeatures.SyncToolSpecification create(NetworkAccessApi networkAccessApi) {
        Map<String, Object> requestIdProperty = new LinkedHashMap<>();
        requestIdProperty.put("type", "string");
        requestIdProperty.put("description", "The requestId returned by a previous request_network_access call");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("requestId", requestIdProperty);

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", java.util.List.of("requestId"));

        McpSchema.Tool tool = McpSchema.Tool.builder("check_network_access_status")
                .description("Check the current status of a previously-submitted network access request "
                        + "without waiting - use after request_network_access returned 'pending'.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handle(networkAccessApi, request))
                .build();
    }

    private static McpSchema.CallToolResult handle(NetworkAccessApi networkAccessApi, McpSchema.CallToolRequest request) {
        Object rawRequestId = request.arguments().get("requestId");
        if (rawRequestId == null || rawRequestId.toString().isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("'requestId' is required.")
                    .build();
        }

        UUID requestId;
        try {
            requestId = UUID.fromString(rawRequestId.toString());
        } catch (IllegalArgumentException e) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("'requestId' is not a valid id: " + rawRequestId)
                    .build();
        }

        return networkAccessApi.getStatus(requestId)
                .map(view -> McpSchema.CallToolResult.builder().addTextContent(RequestNetworkAccessTool.describe(view)).build())
                .orElseGet(() -> McpSchema.CallToolResult.builder()
                        .isError(true)
                        .addTextContent("Unknown requestId: " + requestId)
                        .build());
    }
}
