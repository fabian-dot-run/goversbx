package run.fabian.daemon.mcp;

import run.fabian.daemon.mcp.tool.CheckNetworkAccessStatusTool;
import run.fabian.daemon.mcp.tool.RequestNetworkAccessTool;
import run.fabian.daemon.networkaccess.api.NetworkAccessApi;
import run.fabian.daemon.sandboxidentity.api.SandboxIdentityApi;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * The {@code mcp} bounded context: pure protocol translation between the MCP HTTP transport and
 * {@link NetworkAccessApi}. It has no domain of its own by design. Must never reach into
 * {@code networkaccess.domain}/{@code .application}/{@code .adapter} - only {@code .api}.
 */
public final class McpContext {

    /** Header a sandbox's kit-install step bakes into its MCP registration (see sandbox-kit). */
    public static final String SANDBOX_NAME_HEADER = "X-Goversbx-Sandbox-Name";
    /**
     * Key the raw identity header value is published under in
     * {@link io.modelcontextprotocol.server.McpSyncServerExchange#transportContext()}. This is the
     * proxy-substituted secret token, not a sandbox name - a tool resolves it to a name via
     * {@link run.fabian.daemon.sandboxidentity.api.SandboxIdentityApi#resolveSandboxName(String)}
     * and must never trust it as a name directly.
     */
    public static final String SANDBOX_NAME_CONTEXT_KEY = "sandboxName";

    private final HttpServletStreamableServerTransportProvider servlet;
    private final McpSyncServer server;

    private McpContext(HttpServletStreamableServerTransportProvider servlet, McpSyncServer server) {
        this.servlet = servlet;
        this.server = server;
    }

    public static McpContext build(NetworkAccessApi networkAccessApi, SandboxIdentityApi sandboxIdentityApi) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        HttpServletStreamableServerTransportProvider servlet = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .contextExtractor(request -> McpTransportContext.create(Map.of(
                        SANDBOX_NAME_CONTEXT_KEY, firstNonBlank(request.getHeader(SANDBOX_NAME_HEADER), "unknown-sandbox"))))
                .build();

        McpSyncServer server = McpServer.sync(servlet)
                .serverInfo("goversbx-network", "0.4.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(
                        RequestNetworkAccessTool.create(networkAccessApi, sandboxIdentityApi),
                        CheckNetworkAccessStatusTool.create(networkAccessApi))
                .build();

        return new McpContext(servlet, server);
    }

    /** The raw servlet to mount into the sandbox-facing Jetty instance (e.g. at "/mcp/*"). */
    public HttpServletStreamableServerTransportProvider servlet() {
        return servlet;
    }

    public void close() {
        server.closeGracefully();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
