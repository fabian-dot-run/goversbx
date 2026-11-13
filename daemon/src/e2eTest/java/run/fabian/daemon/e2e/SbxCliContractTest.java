package run.fabian.daemon.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
            // The exec may first have to start the stopped sandbox (49 s seen in the nested dev sandbox),
            // so wait as long as Sandbox.sh itself does.
            busy.get(3, java.util.concurrent.TimeUnit.MINUTES);
        }
    }

    @Test
    void secretSetCustomAcceptsTheFlagsTheDaemonPasses() {
        String help = env.sbx.help("secret", "set-custom").output();
        for (String flag : new String[] {"--sandbox", "--host", "--env", "--placeholder", "--value-stdin", "--no-input"}) {
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
    void lsHasTheFormatFlag() {
        assertTrue(env.sbx.help("ls").output().contains("--format"),
                "sbx ls --help lacks --format (the daemon's default GOVERSBX_SBX_LS_FORMAT_ARGS is '--format json')");
    }

    @Test
    void theKitValidates() {
        // A v3 source kit: validating it builds it with sbx's kit builder (sbx >= 0.51).
        Sbx.Result result = env.sbx.kitValidate(env.config.kit());
        assertTrue(result.ok(), "sbx kit validate " + env.config.kit() + " failed:\n" + result.output());
    }

    /** Spec 0003 slice 3, S25: what scripts/goversbx relies on in `sbx env`. */
    @Test
    void envPlanPrintsTheSandboxNameAndTheEnvVerbsTakeTheLaunchersFlags() throws Exception {
        java.nio.file.Path project = java.nio.file.Files.createDirectories(env.config.runDir.resolve("plan-probe"));
        java.nio.file.Files.writeString(project.resolve("sbxenv.yaml"), "schemaVersion: \"1\"\nagent: shell\nworkspace: .\n");

        // A path argument, so the machine's ~/.sbxenv.yaml plays no part in this check.
        Sbx.Result plan = env.sbx.run("env", "plan", "--name", "goversbx-plan-probe", project.toString());
        assertTrue(plan.ok(), "sbx env plan failed:\n" + plan.output());
        assertTrue(plan.stdout().lines().anyMatch(line -> line.matches(" *Name: +goversbx-plan-probe *")),
                "sbx env plan no longer prints 'Name:   <sandbox>' on stdout - scripts/goversbx reads the name there:\n" + plan.output());

        for (String verb : new String[] {"create", "run"}) {
            String help = env.sbx.help("env", verb).output();
            assertTrue(help.contains("--name") && help.contains("--force"), "sbx env " + verb + " --help lacks --name or --force");
        }
        assertTrue(env.sbx.help("env", "plan").output().contains("--name"), "sbx env plan --help lacks --name");
    }

    /** Spec 0003 slice 4, S14: what `goversbx upgrade` reads and calls. */
    @Test
    void inspectJsonNamesKitsAndCreatingVersionAndKitAddTakesKitArgs() throws Exception {
        Sbx.Result inspect = env.sbx.run("inspect", env.a.name, "--format", "json");
        assertTrue(inspect.ok(), "sbx inspect --format json failed:\n" + inspect.output());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(inspect.stdout());
        assertTrue(json.path("kits").isArray() && json.path("kits").size() >= 2, "inspect JSON has no 'kits' list with the workload and our kit:\n" + inspect.stdout());
        boolean ours = false;
        for (var kit : json.path("kits")) {
            ours |= kit.asText().equals(env.config.kit().toString());
        }
        assertTrue(ours, "inspect JSON does not list the goversbx kit by its directory path - `goversbx upgrade` could not tell it is there:\n" + inspect.stdout());
        assertTrue(json.path("created_version").asText().startsWith("v"), "inspect JSON has no 'created_version':\n" + inspect.stdout());
        assertTrue(json.has("sessions") && json.has("state"), "inspect JSON lacks 'sessions' or 'state'");

        boolean listed = false;
        for (var row : env.sbx.lsJson()) {
            if (row.path("name").asText().equals(env.a.name)) {
                for (var workspace : row.path("workspaces")) {
                    listed |= env.config.workspace != null && workspace.asText().equals(env.config.workspace.toString());
                }
            }
        }
        assertTrue(listed || env.config.workspace == null, "sbx ls --format json does not list the workspace of " + env.a.name + " under 'workspaces'");
        assertTrue(env.sbx.help("kit", "add").output().contains("--kit-arg"), "sbx kit add --help lacks --kit-arg");
    }
}
