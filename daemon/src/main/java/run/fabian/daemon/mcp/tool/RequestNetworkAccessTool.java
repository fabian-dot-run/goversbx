package run.fabian.daemon.mcp.tool;

import run.fabian.daemon.mcp.McpContext;
import run.fabian.daemon.networkaccess.api.NetworkAccessApi;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;
import run.fabian.daemon.sandboxidentity.api.SandboxIdentityApi;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class RequestNetworkAccessTool {

    private RequestNetworkAccessTool() {
    }

    public static McpServerFeatures.SyncToolSpecification create(
            NetworkAccessApi networkAccessApi, SandboxIdentityApi sandboxIdentityApi) {
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

        McpSchema.Tool tool = McpSchema.Tool.builder("request_network_access", inputSchema)
                .description("""
                        Request outbound network access to a host from this sandbox. A human is asked to \
                        approve or deny the request; approval is permanent for this sandbox. Returns \
                        immediately once approved/denied (at once, without asking, if the host was already \
                        granted to this sandbox), or after timeoutSeconds if still pending - in the pending \
                        case, call check_network_access_status later with the returned requestId.""")
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String identityHeader = (String) exchange.transportContext().get(McpContext.SANDBOX_NAME_CONTEXT_KEY);
                    return handle(networkAccessApi, sandboxIdentityApi, identityHeader, request);
                })
                .build();
    }

    private static McpSchema.CallToolResult handle(
            NetworkAccessApi networkAccessApi, SandboxIdentityApi sandboxIdentityApi,
            String identityHeader, McpSchema.CallToolRequest request) {
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

        // The host must be a well-formed, single hostname (threat-model F2: no wildcards, URLs, or IP
        // literals) that doesn't point at the daemon's own admin API (F3) - judged through the
        // published api, which owns both rules, not networkaccess.domain directly.
        Optional<String> hostRejection = networkAccessApi.hostRejectionReason(host);
        if (hostRejection.isPresent()) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent(hostRejection.get())
                    .build();
        }

        // Identity is whatever sandbox this token was minted for - never the raw header value, which a
        // rogue agent could set to anything. An unresolvable value (placeholder, stale token after a
        // daemon restart, or a forged value) triggers registration and a retry; no request is created.
        Optional<String> resolvedSandbox = sandboxIdentityApi.resolveSandboxName(identityHeader);
        if (resolvedSandbox.isEmpty()) {
            sandboxIdentityApi.registerAnyUnregisteredSandboxes();
            return McpSchema.CallToolResult.builder()
                    .addTextContent("This sandbox's identity isn't registered with the daemon yet - "
                            + "registering it now. Please retry request_network_access in a few seconds. "
                            + "If you still get this answer after about three retries, stop retrying: the daemon "
                            + "is reachable but could not register this sandbox with sbx - tell the user to check "
                            + "the goversbx daemon log on the host.")
                    .build();
        }
        String sandboxName = resolvedSandbox.get();

        try {
            NetworkAccessRequestView created = networkAccessApi.createRequest(sandboxName, host, reason);
            NetworkAccessRequestView result = networkAccessApi
                    .awaitDecision(created.id(), Duration.ofSeconds(timeoutSeconds))
                    .orElse(created);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(describe(result))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Failed to process the network access request: " + e.getMessage())
                    .build();
        }
    }

    static String describe(NetworkAccessRequestView r) {
        String what = r.host() != null ? "'" + r.host() + "'"
                : r.hosts().size() + " hosts (" + String.join(", ", r.hosts()) + ")";
        return switch (r.status()) {
            case APPROVED -> "Approved - network access to " + what
                    + " is now allowed for this sandbox. requestId=" + r.id();
            case DENIED -> "Denied - network access to " + what
                    + " was not granted. requestId=" + r.id();
            case PENDING -> "Still pending - no human decision yet for " + what
                    + ". Check back later with check_network_access_status(requestId=\"" + r.id() + "\").";
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
