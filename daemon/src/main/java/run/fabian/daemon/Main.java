package run.fabian.daemon;

import run.fabian.daemon.mcp.McpContext;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
import run.fabian.daemon.networkaccess.adapter.sbx.SbxCliPolicyAdapter;
import run.fabian.daemon.networkaccess.adapter.web.AdminApiController;
import run.fabian.daemon.networkaccess.adapter.web.JsonSupport;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;
import run.fabian.daemon.networkaccess.application.port.RequestRepository;
import run.fabian.daemon.networkaccess.application.port.SbxPolicyPort;
import run.fabian.daemon.sandboxidentity.adapter.sbx.SbxCliCustomSecretAdapter;
import run.fabian.daemon.sandboxidentity.adapter.sbx.SbxCliSandboxListAdapter;
import run.fabian.daemon.sandboxidentity.adapter.web.SandboxIdentityController;
import run.fabian.daemon.sandboxidentity.application.SandboxIdentityService;
import run.fabian.daemon.sandboxidentity.application.port.SbxCustomSecretPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        int sandboxApiPort = intEnv("GOVERSBX_SANDBOX_API_PORT", 8787);
        int adminPort = intEnv("GOVERSBX_ADMIN_PORT", 8788);
        String sandboxApiBind = env("GOVERSBX_SANDBOX_API_BIND", "0.0.0.0");
        String sbxBinary = env("GOVERSBX_SBX_BINARY", "sbx");
        boolean dryRun = Boolean.parseBoolean(env("GOVERSBX_SBX_DRY_RUN", "true"));
        String identityTargetHost = env("GOVERSBX_IDENTITY_TARGET_HOST", "daemon.internal");
        String identityEnvVar = env("GOVERSBX_IDENTITY_ENV_VAR", "GOVERSBX_SANDBOX_TOKEN");
        String identityPlaceholder = env("GOVERSBX_IDENTITY_PLACEHOLDER", "goversbx-sandbox-identity-placeholder-v1");
        Pattern identitySandboxPattern = Pattern.compile(env("GOVERSBX_IDENTITY_SANDBOX_PATTERN", ".*"));
        List<String> lsFormatArgs = List.of(env("GOVERSBX_SBX_LS_FORMAT_ARGS", "--format json").split(" "));

        RequestRepository repository = new InMemoryRequestRepository();
        SbxPolicyPort sbxPolicyPort = new SbxCliPolicyAdapter(sbxBinary, dryRun);
        NetworkAccessService networkAccessService = new NetworkAccessService(repository, sbxPolicyPort);

        SbxCustomSecretPort sbxCustomSecretPort = new SbxCliCustomSecretAdapter(sbxBinary, dryRun);
        SbxSandboxListPort sbxSandboxListPort = new SbxCliSandboxListAdapter(sbxBinary, lsFormatArgs);
        SandboxIdentityService sandboxIdentityService = new SandboxIdentityService(
                sbxCustomSecretPort, sbxSandboxListPort, identityTargetHost, identityEnvVar, identityPlaceholder,
                identitySandboxPattern);

        McpContext mcp = McpContext.build(networkAccessService, sandboxIdentityService);

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
        new SandboxIdentityController(sandboxIdentityService).registerRoutes(adminApp);
        adminApp.start("127.0.0.1", adminPort);
        log.info("Admin UI + API listening on 127.0.0.1:{} (loopback only)", adminPort);

        if (dryRun) {
            log.warn("GOVERSBX_SBX_DRY_RUN=true - approvals and sandbox-identity registration will NOT actually call the sbx CLI");
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
