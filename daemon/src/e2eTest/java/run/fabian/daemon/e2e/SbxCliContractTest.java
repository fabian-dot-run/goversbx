package run.fabian.daemon.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The installed sbx CLI still has the shape the daemon and the kits assume. sbx's flags have churned
 * before (`--target` -> `--host`, `--json` -> `--format json`); this is where that shows up first.
 */
@Order(1)
@ExtendWith(E2eSuite.class)
class SbxCliContractTest {

    private final E2eEnvironment env = E2eEnvironment.get();

    @Test
    void lsFormatJsonHasTheFieldsTheDaemonReads() {
        JsonNode rows = env.sbx.lsJson();
        assertTrue(rows.isArray(), "sbx ls --format json must print an array: " + rows);
        JsonNode ours = env.sbx.sandbox(env.a.name).orElseThrow(() -> new AssertionError(env.a + " missing from sbx ls: " + rows));
        for (String field : new String[] {"name", "id", "status"}) {
            assertTrue(ours.hasNonNull(field), "sbx ls row lacks '" + field + "' (SandboxSummary reads it): " + ours);
        }
        String status = ours.path("status").asText();
        assertTrue(status.equals("running") || status.equals("stopped"), "unexpected sbx ls status '" + status + "': " + ours);
    }

    @Test
    void lsReportsASandboxAsRunningWhileACommandRunsInIt() throws Exception {
        // sbx 0.49.1 stops an idle sandbox (nothing attached) after a short while and starts it again
        // on `sbx exec`; the daemon only registers sandboxes `sbx ls` reports as running, which is
        // exactly the ones that can be making MCP calls at that moment. Pin that this is what ls says.
        var busy = java.util.concurrent.CompletableFuture.supplyAsync(() -> env.a.sh("sleep 25; echo done"));
        try {
            String status = "";
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            while (!status.equals("running") && System.nanoTime() < deadline) {
                status = env.sbx.sandbox(env.a.name).map(row -> row.path("status").asText()).orElse("<missing>");
                if (!status.equals("running")) {
                    Daemon.sleep(1000);
                }
            }
            assertEquals("running", status, "sbx ls does not report " + env.a + " as running while a command runs in it");
        } finally {
            busy.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void secretSetCustomAcceptsTheFlagsTheDaemonPasses() {
        String help = env.sbx.help("secret", "set-custom").output();
        for (String flag : new String[] {"--sandbox", "--host", "--env", "--placeholder", "--value"}) {
            assertTrue(help.contains(flag), "sbx secret set-custom --help no longer documents " + flag
                    + " (SbxCliCustomSecretAdapter passes it)");
        }
    }

    @Test
    void policyAllowNetworkIsSandboxScopable() {
        String help = env.sbx.help("policy", "allow", "network").output();
        assertTrue(help.contains("--sandbox"), "sbx policy allow network --help lacks --sandbox (SbxCliPolicyAdapter passes it)");
    }

    @Test
    void policyAllowNetworkTwiceForTheSameHostSucceedsBothTimes() {
        // Spec 0001 open question 2: the daemon re-requests a sandbox's whole host set after a restart
        // (grants are in memory), so allowing an already allowed host must not count as a failure.
        String host = "goversbx-e2e-idempotency-check.invalid";
        Sbx.Result first = env.sbx.policyAllowNetwork(host, env.a.name);
        assertTrue(first.ok(), "first sbx policy allow failed:\n" + first.output());
        Sbx.Result second = env.sbx.policyAllowNetwork(host, env.a.name);
        assertTrue(second.ok(), "sbx policy allow for an EXISTING rule exits " + second.exit() + " - the daemon would report "
                + "a re-requested host as failed:\n" + second.output());
    }

    @Test
    void lsHasTheFormatFlag() {
        assertTrue(env.sbx.help("ls").output().contains("--format"),
                "sbx ls --help lacks --format (the daemon's default GOVERSBX_SBX_LS_FORMAT_ARGS is '--format json')");
    }

    @ParameterizedTest(name = "kit {0} validates")
    @ValueSource(strings = {"claude", "pi", "omp", "opencode"})
    void everyKitValidates(String kit) {
        Sbx.Result result = env.sbx.kitValidate(env.config.kit(kit));
        assertTrue(result.ok(), "sbx kit validate goversbx-" + kit + " failed:\n" + result.output());
    }
}
