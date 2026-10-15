package run.fabian.daemon.mcp;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
import run.fabian.daemon.networkaccess.adapter.sbx.SbxCliPolicyAdapter;
import run.fabian.daemon.networkaccess.api.RequestStatus;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;
import run.fabian.daemon.sandboxidentity.api.SandboxIdentityApi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real MCP HTTP endpoint with the MCP Java SDK's own sync client - a genuine
 * protocol-level integration test, not hand-rolled JSON-RPC.
 */
class McpEndpointIntegrationTest {

    private static Javalin newApp(McpContext mcp) {
        return Javalin.create(config -> config.jetty.modifyServletContextHandler(handler ->
                handler.addServlet(new ServletHolder(mcp.servlet()), "/mcp/*")));
    }

    /** Resolves every header to one fixed sandbox - for tests not exercising the registration path. */
    private static SandboxIdentityApi noopSandboxIdentity() {
        return new SandboxIdentityApi() {
            @Override
            public java.util.Optional<String> resolveSandboxName(String identityHeaderValue) {
                return java.util.Optional.of("test-sandbox");
            }

            @Override
            public void registerAnyUnregisteredSandboxes() {
                throw new AssertionError("should not be called when the identity resolves");
            }
        };
    }

    @Test
    void listsBothTools() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new SbxCliPolicyAdapter("sbx", true));
        McpContext mcp = McpContext.build(service, noopSandboxIdentity(), 8788);

        JavalinTest.test(newApp(mcp), (server, client) -> {
            try (McpSyncClient mcpClient = connect(client.getOrigin())) {
                McpSchema.ListToolsResult tools = mcpClient.listTools();
                List<String> names = tools.tools().stream().map(McpSchema.Tool::name).toList();
                assertTrue(names.contains("request_network_access"));
                assertTrue(names.contains("check_network_access_status"));
            }
        });
    }

    @Test
    void requestNetworkAccessReturnsPendingWhenNobodyDecides() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new SbxCliPolicyAdapter("sbx", true));
        McpContext mcp = McpContext.build(service, noopSandboxIdentity(), 8788);

        JavalinTest.test(newApp(mcp), (server, client) -> {
            try (McpSyncClient mcpClient = connect(client.getOrigin())) {
                McpSchema.CallToolResult result = mcpClient.callTool(McpSchema.CallToolRequest.builder("request_network_access")
                        .arguments(Map.of("host", "api.example.com", "reason", "smoke test", "timeoutSeconds", 1))
                        .build());

                String text = firstTextContent(result);
                assertTrue(text.contains("Still pending"), text);
            }
        });
    }

    @Test
    void requestNetworkAccessUnblocksAsSoonAsApproved() throws Exception {
        InMemoryRequestRepository repository = new InMemoryRequestRepository();
        NetworkAccessService service = new NetworkAccessService(repository, new SbxCliPolicyAdapter("sbx", true));
        McpContext mcp = McpContext.build(service, noopSandboxIdentity(), 8788);

        ExecutorService approver = Executors.newSingleThreadExecutor();
        try {
            JavalinTest.test(newApp(mcp), (server, client) -> {
                approver.submit(() -> {
                    UUID pendingId = pollForPendingRequestId(service);
                    service.approve(pendingId, "fabian");
                });

                try (McpSyncClient mcpClient = connect(client.getOrigin())) {
                    McpSchema.CallToolResult result = mcpClient.callTool(McpSchema.CallToolRequest.builder("request_network_access")
                            .arguments(Map.of("host", "api.example.com", "reason", "smoke test", "timeoutSeconds", 10))
                            .build());

                    String text = firstTextContent(result);
                    assertTrue(text.contains("Approved"), text);
                }
            });
        } finally {
            approver.shutdownNow();
            approver.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void requestNetworkAccessTriggersRegistrationAndAsksForRetryWhenSandboxIsUnregistered() throws Exception {
        String placeholder = "the-placeholder";
        AtomicBoolean registrationTriggered = new AtomicBoolean(false);
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new SbxCliPolicyAdapter("sbx", true));
        SandboxIdentityApi sandboxIdentityApi = new SandboxIdentityApi() {
            @Override
            public java.util.Optional<String> resolveSandboxName(String identityHeaderValue) {
                // The raw placeholder (or any unknown value) doesn't resolve: the sandbox isn't registered.
                return java.util.Optional.empty();
            }

            @Override
            public void registerAnyUnregisteredSandboxes() {
                registrationTriggered.set(true);
            }
        };
        McpContext mcp = McpContext.build(service, sandboxIdentityApi, 8788);

        JavalinTest.test(newApp(mcp), (server, client) -> {
            try (McpSyncClient mcpClient = connectWithHeader(client.getOrigin(), McpContext.SANDBOX_NAME_HEADER, placeholder)) {
                McpSchema.CallToolResult result = mcpClient.callTool(McpSchema.CallToolRequest.builder("request_network_access")
                        .arguments(Map.of("host", "api.example.com", "reason", "smoke test", "timeoutSeconds", 1))
                        .build());

                String text = firstTextContent(result);
                assertTrue(text.contains("registering it now"), text);
            }
        });

        assertTrue(registrationTriggered.get());
        assertEquals(0, service.list(RequestStatus.PENDING).size());
    }

    private static UUID pollForPendingRequestId(NetworkAccessService service) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var pending = service.list(RequestStatus.PENDING);
            if (!pending.isEmpty()) {
                return pending.get(0).id();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException("no pending request appeared within 5s");
    }

    private static McpSyncClient connect(String origin) {
        McpSyncClient mcpClient = McpClient
                .sync(HttpClientStreamableHttpTransport.builder(origin + "/mcp").build())
                .build();
        mcpClient.initialize();
        return mcpClient;
    }

    private static McpSyncClient connectWithHeader(String origin, String headerName, String headerValue) {
        McpSyncClient mcpClient = McpClient
                .sync(HttpClientStreamableHttpTransport.builder(origin + "/mcp")
                        .httpRequestCustomizer((builder, method, uri, body, ctx) -> builder.header(headerName, headerValue))
                        .build())
                .build();
        mcpClient.initialize();
        return mcpClient;
    }

    private static String firstTextContent(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no text content in result: " + result));
    }

    @Test
    void checkStatusForUnknownIdIsError() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new SbxCliPolicyAdapter("sbx", true));
        McpContext mcp = McpContext.build(service, noopSandboxIdentity(), 8788);

        JavalinTest.test(newApp(mcp), (server, client) -> {
            try (McpSyncClient mcpClient = connect(client.getOrigin())) {
                McpSchema.CallToolResult result = mcpClient.callTool(McpSchema.CallToolRequest.builder("check_network_access_status")
                        .arguments(Map.of("requestId", UUID.randomUUID().toString()))
                        .build());

                assertEquals(Boolean.TRUE, result.isError());
            }
        });
    }
}
