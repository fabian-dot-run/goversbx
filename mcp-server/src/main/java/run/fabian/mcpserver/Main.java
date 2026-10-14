package run.fabian.mcpserver;

import run.fabian.mcpserver.tools.CheckNetworkAccessStatusTool;
import run.fabian.mcpserver.tools.RequestNetworkAccessTool;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String daemonUrl = env("GOVERSBX_DAEMON_URL", "http://daemon.internal:8787");
        String sandboxName = env("SANDBOX_NAME", env("SANDBOX_VM_ID", "unknown-sandbox"));

        DaemonClient daemonClient = new DaemonClient(daemonUrl, sandboxName);

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(jsonMapper);

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("goversbx-network", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(
                        RequestNetworkAccessTool.create(daemonClient),
                        CheckNetworkAccessStatusTool.create(daemonClient))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));

        log.info("goversbx-network MCP server ready (sandbox={}, daemon={})", sandboxName, daemonUrl);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
