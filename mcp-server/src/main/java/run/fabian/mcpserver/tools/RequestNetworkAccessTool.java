package run.fabian.mcpserver.tools;

import run.fabian.common.NetworkAccessRequest;
import run.fabian.mcpserver.DaemonClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RequestNetworkAccessTool {

    private RequestNetworkAccessTool() {
    }

    public static McpServerFeatures.SyncToolSpecification create(DaemonClient daemonClient) {
        Map<String, Object> hostProperty = new LinkedHashMap<>();
        hostProperty.put("type", "string");
        hostProperty.put("description", "Domain or host the agent needs to reach, e.g. api.example.com");

        Map<String, Object> reasonProperty = new LinkedHashMap<>();
        reasonProperty.put("type", "string");
        reasonProperty.put("description", "Why this access is needed - shown to the human approver");

        Map<String, Object> timeoutProperty = new LinkedHashMap<>();
        timeoutProperty.put("type", "integer");
        timeoutProperty.put("minimum", 1);
        timeoutProperty.put("maximum", 120);
        timeoutProperty.put("default", 45);
        timeoutProperty.put("description", "How long to wait for a human decision before returning 'pending'");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host", hostProperty);
        properties.put("reason", reasonProperty);
        properties.put("timeoutSeconds", timeoutProperty);

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", java.util.List.of("host", "reason"));

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("request_network_access")
                .description("""
                        Request outbound network access to a host from this sandbox. A human is asked to \
                        approve or deny the request; approval is permanent for this sandbox. Returns \
                        immediately once approved/denied, or after timeoutSeconds if still pending - in the \
                        pending case, call check_network_access_status later with the returned requestId.""")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handle(daemonClient, request))
                .build();
    }

    private static McpSchema.CallToolResult handle(DaemonClient daemonClient, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String host = stringArg(args, "host");
        String reason = stringArg(args, "reason");
        int timeoutSeconds = clamp(intArg(args, "timeoutSeconds", 45), 1, 120);

        if (host == null || host.isBlank() || reason == null || reason.isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Both 'host' and 'reason' are required.")
                    .build();
        }

        try {
            NetworkAccessRequest created = daemonClient.createRequest(host, reason);
            NetworkAccessRequest result = daemonClient.awaitDecision(created.getId().toString(), timeoutSeconds);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(describe(result))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Failed to reach the goversbx daemon: " + e.getMessage())
                    .build();
        }
    }

    static String describe(NetworkAccessRequest r) {
        return switch (r.getStatus()) {
            case APPROVED -> "Approved - network access to '" + r.getHost()
                    + "' is now allowed for this sandbox. requestId=" + r.getId();
            case DENIED -> "Denied - network access to '" + r.getHost()
                    + "' was not granted. requestId=" + r.getId();
            case PENDING -> "Still pending - no human decision yet for '" + r.getHost()
                    + "'. Check back later with check_network_access_status(requestId=\"" + r.getId() + "\").";
        };
    }

    private static String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value == null ? null : value.toString();
    }

    private static int intArg(Map<String, Object> args, String name, int defaultValue) {
        Object value = args.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
