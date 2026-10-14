package run.fabian.daemon;

import run.fabian.daemon.mcp.McpContext;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
import run.fabian.daemon.networkaccess.adapter.sbx.SbxCliPolicyAdapter;
import run.fabian.daemon.networkaccess.adapter.web.AdminApiController;
import run.fabian.daemon.networkaccess.adapter.web.JsonSupport;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;
import run.fabian.daemon.networkaccess.application.port.RequestRepository;
import run.fabian.daemon.networkaccess.application.port.SbxPolicyPort;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        int sandboxApiPort = intEnv("GOVERSBX_SANDBOX_API_PORT", 8787);
        int adminPort = intEnv("GOVERSBX_ADMIN_PORT", 8788);
        String sandboxApiBind = env("GOVERSBX_SANDBOX_API_BIND", "0.0.0.0");
        String sbxBinary = env("GOVERSBX_SBX_BINARY", "sbx");
        boolean dryRun = Boolean.parseBoolean(env("GOVERSBX_SBX_DRY_RUN", "true"));

        RequestRepository repository = new InMemoryRequestRepository();
        SbxPolicyPort sbxPolicyPort = new SbxCliPolicyAdapter(sbxBinary, dryRun);
        NetworkAccessService networkAccessService = new NetworkAccessService(repository, sbxPolicyPort);

        McpContext mcp = McpContext.build(networkAccessService);

        Javalin sandboxApp = Javalin.create(config ->
                config.jetty.modifyServletContextHandler(handler ->
                        handler.addServlet(new ServletHolder(mcp.servlet()), "/mcp/*")));
        sandboxApp.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
        sandboxApp.start(sandboxApiBind, sandboxApiPort);
        log.info("Sandbox-facing MCP endpoint listening on {}:{}/mcp", sandboxApiBind, sandboxApiPort);

        Javalin adminApp = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(JsonSupport.MAPPER, false));
            config.staticFiles.add("/public");
        });
        new AdminApiController(networkAccessService).registerRoutes(adminApp);
        adminApp.start("127.0.0.1", adminPort);
        log.info("Admin UI + API listening on 127.0.0.1:{} (loopback only)", adminPort);

        if (dryRun) {
            log.warn("GOVERSBX_SBX_DRY_RUN=true - approvals will NOT actually call the sbx CLI");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mcp.close();
            sandboxApp.stop();
            adminApp.stop();
        }));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }
}
