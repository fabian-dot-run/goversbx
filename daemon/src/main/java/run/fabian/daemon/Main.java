package run.fabian.daemon;

import run.fabian.daemon.http.AdminServer;
import run.fabian.daemon.http.SandboxApiServer;
import run.fabian.daemon.sbx.SbxPolicyClient;
import run.fabian.daemon.store.RequestStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        int sandboxApiPort = intEnv("GOVERSBX_SANDBOX_API_PORT", 8787);
        int adminPort = intEnv("GOVERSBX_ADMIN_PORT", 8788);
        String sandboxApiBind = env("GOVERSBX_SANDBOX_API_BIND", "0.0.0.0");
        String sbxBinary = env("GOVERSBX_SBX_BINARY", "sbx");
        boolean dryRun = Boolean.parseBoolean(env("GOVERSBX_SBX_DRY_RUN", "true"));

        RequestStore store = new RequestStore();
        SbxPolicyClient sbxPolicyClient = new SbxPolicyClient(sbxBinary, dryRun);

        SandboxApiServer sandboxApiServer = new SandboxApiServer(store);
        AdminServer adminServer = new AdminServer(store, sbxPolicyClient);

        sandboxApiServer.start(sandboxApiBind, sandboxApiPort);
        log.info("Sandbox-facing API listening on {}:{}", sandboxApiBind, sandboxApiPort);

        adminServer.start("127.0.0.1", adminPort);
        log.info("Admin UI + API listening on 127.0.0.1:{} (loopback only)", adminPort);

        if (dryRun) {
            log.warn("GOVERSBX_SBX_DRY_RUN=true - approvals will NOT actually call the sbx CLI");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sandboxApiServer.stop();
            adminServer.stop();
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
